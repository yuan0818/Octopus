package io.yuan.octopus.review.service;

import io.yuan.octopus.infra.memory.service.MemoryManager;
import io.yuan.octopus.review.dto.*;
import io.yuan.octopus.review.model.*;
import io.yuan.octopus.review.parsing.RequirementParser;
import io.yuan.octopus.review.skill.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewModelSelectionTest {
    private final ModelRegistry registry = mock(ModelRegistry.class);
    private final SkillRegistry skills = mock(SkillRegistry.class);
    private final SkillExecutor executor = mock(SkillExecutor.class);
    private final CodeRetrievalService code = mock(CodeRetrievalService.class);
    private final PromptBuilder prompts = mock(PromptBuilder.class);
    private final RequirementParser requirements = mock(RequirementParser.class);
    private final Skill skill = new Skill("review", "Review", "", "review", "deepseek", "review", 4096, true);
    private ReviewOrchestrator orchestrator;
    private ReviewRequest request;
    private ModelConfig selected;

    @BeforeEach
    void setup() {
        orchestrator = new ReviewOrchestrator(mock(MemoryManager.class), code, requirements, prompts,
                null, null, null, skills, executor, null, registry);
        request = new ReviewRequest();
        request.setProjectId(1L);
        request.setClassName("ExampleController");
        request.setMethodName("listAll");
        request.setModelName("qa-config");
        when(skills.resolve(any())).thenReturn(skill);
        selected = new ModelConfig();
        selected.setName("qa-config");
        selected.setProvider("Nothing");
        selected.setModelId("qwen3.8-27b");
        selected.setApiFormat("openai");
        selected.setApiUrl("http://localhost/model-fixture");
        selected.setApiKey("fixture-key");
        selected.setMaxTokens(8192);
        selected.setTemperature(0.7);
        selected.setEnabled(true);
        when(registry.get("qa-config")).thenReturn(selected);
        when(code.getChainMetadata(any(), any(), any(), any(), any(), any())).thenReturn(Map.of());
        when(code.getSourceCode(any(), any(), any(), any(), any(), any())).thenReturn(Map.of());
        when(requirements.parse(request)).thenReturn(List.of());
    }

    @Test
    void validatesChosenConfigurationWithoutRequiringSkillsDefaultProvider() {
        var result = orchestrator.validate(request);
        assertTrue(result.valid());
        assertTrue(result.warnings().contains("Using qwen3.8-27b via Nothing"));
        verify(registry, never()).select(any(), any());
        verify(registry, never()).getApiKey(any());
    }

    @Test
    void missingOrDisabledSelectionCannotSilentlyFallBack() {
        when(registry.get("qa-config")).thenReturn(null);
        assertTrue(orchestrator.validate(request).errors().getFirst().contains("不存在"));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.review(request));
        when(registry.get("qa-config")).thenReturn(selected);
        selected.setEnabled(false);
        assertTrue(orchestrator.validate(request).errors().getFirst().contains("已禁用"));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.review(request));
        verify(registry, never()).select(any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void keyIsCheckedOnExactConfigRatherThanAnotherConfigWithSameProvider() {
        selected.setApiKey("");
        assertFalse(orchestrator.validate(request).valid());
        verify(registry, never()).getApiKey(any());
    }

    @Test
    void unspecifiedModelUsesSameFallbackForValidationAndExecution() {
        request.setModelName(null);
        when(registry.getAllConfigs()).thenReturn(Map.of("qa-config", selected));
        assertTrue(orchestrator.validate(request).valid());
        assertExecutionConfig();
    }

    @Test
    void executionUsesServerConnectionAndModelDefaultsWithoutMutatingRequest() {
        assertExecutionConfig();
        assertNull(request.getLlmConfig());
        LLMConfig input = new LLMConfig();
        input.setApiUrl("http://untrusted");
        input.setApiKey("untrusted");
        input.setModel("untrusted");
        input.setMaxTokens(null);
        input.setTemperature(null);
        request.setLlmConfig(input);
        assertExecutionConfig();
        assertEquals("untrusted", input.getApiKey());
        assertEquals("http://untrusted", input.getApiUrl());
    }

    @Test
    void previewReportsSelectedProvider() {
        when(prompts.getSystemPrompt(skill)).thenReturn("system");
        when(prompts.build(eq(skill), any(), any(), any(), any(), any(), any())).thenReturn("user");
        assertEquals("Nothing", orchestrator.preview(request).provider());
    }

    @Test
    void actualSkillPipelineCallsSelectedHttpModelDespiteLegacySkillProvider() throws Exception {
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new com.google.gson.Gson().toJson(Map.of("choices", List.of(Map.of("message",
                    Map.of("content", "{\"issues\":[],\"requirementChecks\":[]}"))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            selected.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var manager = mock(io.yuan.octopus.review.prompt.PromptManager.class);
            var validator = new SkillValidator(manager);
            assertTrue(validator.validate(skill).valid());
            assertTrue(validator.validate(skill).warnings().isEmpty());
            when(prompts.getSystemPrompt(skill)).thenReturn("system");
            when(prompts.build(eq(skill), any(), any(), any(), any(), any(), any())).thenReturn("user");
            when(prompts.getOutputSchema(skill)).thenReturn("{}");
            var llm = new LLMIntegrationService(List.of(new io.yuan.octopus.review.adapter.HttpLLMAdapter()), registry);
            var realExecutor = new SkillExecutor(prompts, llm, new ReviewResultParser(), new SchemaValidator(), validator);
            var review = new ReviewOrchestrator(mock(MemoryManager.class), code, requirements, prompts, llm,
                    new ReviewResultParser(), mock(ProjectPatternExtractor.class), skills, realExecutor, manager, registry);
            assertTrue(review.validate(request).valid());
            assertNotNull(review.review(request).getSessionId());
            var body = com.google.gson.JsonParser.parseString(received.get()).getAsJsonObject();
            assertEquals("qwen3.8-27b", body.get("model").getAsString());
            assertEquals("Bearer fixture-key", authorization.get());
            verify(registry, never()).select(any(), any());
            verify(registry, never()).getApiKey(any());
            var config = new LLMConfig();
            config.setProvider("Nothing");
            config.setModel("qwen3.8-27b");
            var ctx = new SkillContext("1", "C", "m", "BFS", List.of(), "", Map.of(), Map.of(), config);
            var plan = realExecutor.plan(skill, ctx);
            assertEquals("Nothing", plan.getProvider());
            assertEquals("qwen3.8-27b", plan.getModelId());
            var noModel = new SkillContext("1", "C", "m", "BFS", List.of(), "", Map.of(), Map.of(), null);
            assertFalse(realExecutor.execute(skill, noModel).success());
        } finally {
            server.stop(0);
        }
    }

    private void assertExecutionConfig() {
        var stop = new IllegalStateException("stop before remote call");
        when(executor.execute(eq(skill), any())).thenAnswer(inv -> {
            LLMConfig actual = ((SkillContext) inv.getArgument(1)).getLlmConfig();
            assertEquals("Nothing", actual.getProvider());
            assertEquals("qwen3.8-27b", actual.getModel());
            assertEquals("openai", actual.getApiFormat());
            assertEquals("http://localhost/model-fixture", actual.getApiUrl());
            assertEquals("fixture-key", actual.getApiKey());
            assertEquals(8192, actual.getMaxTokens());
            assertEquals(0.7, actual.getTemperature());
            throw stop;
        });
        assertSame(stop, assertThrows(IllegalStateException.class, () -> orchestrator.review(request)));
    }
}
