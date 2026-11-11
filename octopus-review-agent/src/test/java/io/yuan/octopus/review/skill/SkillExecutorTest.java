package io.yuan.octopus.review.skill;

import io.yuan.octopus.review.adapter.LLMAdapter.LLMResponse;
import io.yuan.octopus.review.dto.*;
import io.yuan.octopus.review.model.ModelConfig;
import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.service.LLMIntegrationService;
import io.yuan.octopus.review.service.PromptBuilder;
import io.yuan.octopus.review.service.ReviewResultParser;
import io.yuan.octopus.review.service.SchemaValidationException;
import io.yuan.octopus.review.service.SchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillExecutorTest {

    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private LLMIntegrationService llmIntegration;
    @Mock
    private ReviewResultParser resultParser;
    @Mock
    private SchemaValidator schemaValidator;
    @Mock
    private SkillValidator skillValidator;
    @Mock
    private ModelRegistry modelRegistry;

    private SkillExecutor executor;

    private Skill skill;
    private SkillContext context;

    @BeforeEach
    void setUp() {
        executor = new SkillExecutor(promptBuilder, llmIntegration, resultParser,
                schemaValidator, skillValidator);

        skill = new Skill("test-skill", "Test Skill", "test",
                "prompt-1", "openai", "requirement-check", 128000, true);

        lenient().when(promptBuilder.getSystemPrompt(any())).thenReturn("You are a reviewer.");
        lenient().when(promptBuilder.build(any(), any(), any(), any(), any(), any(), any())).thenReturn("Review this code.");
        lenient().when(promptBuilder.getOutputSchema(any())).thenReturn("{\"issues\":[]}");

        SkillValidator.ValidationResult validResult = new SkillValidator.ValidationResult(true, List.of(), List.of());
        lenient().when(skillValidator.validate(any())).thenReturn(validResult);

        LLMConfig llmConfig = new LLMConfig();
        llmConfig.setProvider("openai");
        llmConfig.setModel("gpt-4");

        context = new SkillContext(
                "1", "com.x.Controller", "create", "BFS",
                List.of(), "", Map.of(), Map.of(), llmConfig, ReviewMode.REQUIREMENT_AWARE
        );
    }

    // ── 首次校验通过 → 直接返回成功 ─────────────────────────────

    @Test
    void shouldReturnSuccessWhenFirstValidationPasses() throws Exception {
        LLMResponse llmResponse = new LLMResponse("{}", "gpt-4", 100, 50);
        ReviewResponse parsed = validReviewResponse();

        when(llmIntegration.call(anyString(), anyString(), any())).thenReturn(llmResponse);
        when(resultParser.parse(anyString(), any(), any())).thenReturn(parsed);
        // schemaValidator.validate passes (no exception)

        SkillResult result = executor.execute(skill, context);

        assertTrue(result.success());
        assertNotNull(result.reviewResponse());
        assertEquals("test-skill", result.skillId());
        verify(llmIntegration, times(1)).call(anyString(), anyString(), any());
    }

    // ── 校验失败 → 重试后通过 ─────────────────────────────────────

    @Test
    void shouldRetryAndSucceedOnSecondAttempt() throws Exception {
        LLMResponse llmResponse = new LLMResponse("{}", "gpt-4", 100, 50);
        ReviewResponse parsed = validReviewResponse();

        when(llmIntegration.call(anyString(), anyString(), any())).thenReturn(llmResponse);
        when(resultParser.parse(anyString(), any(), any())).thenReturn(parsed);
        // First call fails, second passes
        doThrow(new SchemaValidationException("issues 为空", List.of("缺失 issues")))
                .doNothing()
                .when(schemaValidator).validate(any());

        SkillResult result = executor.execute(skill, context);

        assertTrue(result.success());
        assertNotNull(result.reviewResponse());
        verify(llmIntegration, times(2)).call(anyString(), anyString(), any());
    }

    // ── 重试耗尽 → 返回诊断 ReviewResponse ───────────────────────

    @Test
    void shouldReturnDiagnosticResponseWhenRetriesExhausted() throws Exception {
        LLMResponse llmResponse = new LLMResponse("{}", "gpt-4", 100, 50);
        ReviewResponse parsed = validReviewResponse();

        when(llmIntegration.call(anyString(), anyString(), any())).thenReturn(llmResponse);
        when(resultParser.parse(anyString(), any(), any())).thenReturn(parsed);
        // All validations fail
        doThrow(new SchemaValidationException("校验失败", List.of("缺失 issues")))
                .when(schemaValidator).validate(any());

        SkillResult result = executor.execute(skill, context);

        assertTrue(result.success());
        assertNotNull(result.reviewResponse());
        // 兜底响应应包含 category=SYSTEM 的诊断 issue
        ReviewResponse diagResponse = result.reviewResponse();
        assertTrue(diagResponse.getIssues().stream()
                .anyMatch(i -> "SYSTEM".equals(i.getCategory())));
        assertEquals("PARSING_FAILED", diagResponse.getSummary().getStatus());
        // 应该调用 3 次 LLM（首次 + 2 次重试）
        verify(llmIntegration, times(3)).call(anyString(), anyString(), any());
    }

    // ── LLM 调用失败 → 重试后仍失败 → 兜底响应 ─────────────────

    @Test
    void shouldReturnFallbackWhenLlmCallFailsOnAllAttempts() throws Exception {
        when(llmIntegration.call(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("LLM service unavailable"));

        SkillResult result = executor.execute(skill, context);

        assertTrue(result.success());
        assertNotNull(result.reviewResponse());
        assertTrue(result.reviewResponse().getIssues().stream()
                .anyMatch(i -> "SYSTEM".equals(i.getCategory())));
    }

    // ── Skill validation 失败 → 返回 failure ─────────────────────

    @Test
    void shouldReturnFailureWhenSkillValidationFails() {
        // 使用 lenient 避免因 @BeforeEach 中的 promptBuilder stub 未被使用而报错
        SkillValidator.ValidationResult invalidResult =
                new SkillValidator.ValidationResult(false, List.of(), List.of("Skill not found"));
        when(skillValidator.validate(any())).thenReturn(invalidResult);

        SkillResult result = executor.execute(skill, context);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Skill validation failed"));
        verify(llmIntegration, never()).call(anyString(), anyString(), any());
    }

    // ── 解析失败 → 重试后通过 ─────────────────────────────────────

    @Test
    void shouldRetryWhenParseFailsThenSucceed() throws Exception {
        LLMResponse llmResponse = new LLMResponse("{}", "gpt-4", 100, 50);
        ReviewResponse parsed = validReviewResponse();

        when(llmIntegration.call(anyString(), anyString(), any())).thenReturn(llmResponse);
        // First parse fails, second succeeds
        when(resultParser.parse(anyString(), any(), any()))
                .thenThrow(new RuntimeException("Invalid JSON"))
                .thenReturn(parsed);

        SkillResult result = executor.execute(skill, context);

        assertTrue(result.success());
        assertNotNull(result.reviewResponse());
        verify(llmIntegration, times(2)).call(anyString(), anyString(), any());
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ReviewResponse validReviewResponse() {
        ReviewResponse response = new ReviewResponse();
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("BLOCKER");
        issue.setCategory("SECURITY");
        issue.setDescription("SQL injection risk");
        response.setIssues(List.of(issue));
        response.setSummary(new ReviewSummary(0, 0, 0, 1, "BLOCKER", "OK"));
        return response;
    }
}
