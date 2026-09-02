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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a Skill's full pipeline:
 * 1. Load prompt template → 2. Assemble prompt with context
 * 3. Call LLM → 4. Parse result → 5. Validate schema
 * 6. Retry on validation failure (max 2 retries)
 */
@Component
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1000L;

    private final PromptBuilder promptBuilder;
    private final LLMIntegrationService llmIntegration;
    private final ReviewResultParser resultParser;
    private final SchemaValidator schemaValidator;
    private final SkillValidator skillValidator;
    private final ModelRegistry modelRegistry;

    public SkillExecutor(PromptBuilder promptBuilder,
                         LLMIntegrationService llmIntegration,
                         ReviewResultParser resultParser,
                         SchemaValidator schemaValidator,
                         SkillValidator skillValidator,
                         ModelRegistry modelRegistry) {
        this.promptBuilder = promptBuilder;
        this.llmIntegration = llmIntegration;
        this.resultParser = resultParser;
        this.schemaValidator = schemaValidator;
        this.skillValidator = skillValidator;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Execute a skill with the given context, with schema validation retry.
     */
    public SkillResult execute(Skill skill, SkillContext context) {
        long startTime = System.currentTimeMillis();
        String skillId = skill.getId();

        log.info("Executing skill '{}' on {}.{}", skillId, context.getClassName(), context.getMethodName());

        // Step 1: Validate skill can execute
        SkillValidator.ValidationResult validation = skillValidator.validate(skill);
        if (!validation.valid()) {
            return SkillResult.failure(skillId, "Skill validation failed: " + String.join("; ", validation.errors()));
        }

        // Step 2: Assemble prompts
        String systemPrompt = promptBuilder.getSystemPrompt(skill);
        String userPrompt = promptBuilder.build(skill, context.getProjectKnowledge(),
                context.getChainMetadata(), context.getRequirements(), context.getSourceCode(),
                context.getReviewMode(), context.getDiffContext());
        String outputSchema = promptBuilder.getOutputSchema(skill);

        int estimatedTokens = (systemPrompt.length() + userPrompt.length()) / 4;

        // Step 3: Resolve LLM config
        LLMConfig llmConfig = context.getLlmConfig() != null ? context.getLlmConfig() : new LLMConfig();
        if (llmConfig.getProvider() == null) {
            llmConfig.setProvider(skill.getPreferredProvider());
        }

        // Step 4-6: Call LLM → Parse → Validate → Retry loop
        LLMResponse llmResponse = null;
        ReviewResponse parsed = null;
        List<Map<String, Object>> retrySteps = new ArrayList<>();
        String currentUserPrompt = userPrompt;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                // 构造修正提示
                StringBuilder correction = new StringBuilder();
                correction.append("\n\n【修正提示】之前的 LLM 输出未通过结构化校验。");
                correction.append("请严格按照以下格式输出 JSON，不要添加任何其他内容：\n");
                correction.append(outputSchema != null ? outputSchema : "{\"requirementChecks\":[...],\"issues\":[...]}");
                currentUserPrompt = userPrompt + correction.toString();

                log.info("Retry attempt {}/{} with correction prompt", attempt, MAX_RETRIES);
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Call LLM
            try {
                llmResponse = llmIntegration.call(systemPrompt, currentUserPrompt, llmConfig);
            } catch (Exception e) {
                log.warn("LLM call failed on attempt {}/{}: {}", attempt + 1, MAX_RETRIES + 1, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    parsed = buildFallbackResponse("LLM 调用失败: " + e.getMessage());
                    break;
                }
                continue;
            }

            // Parse result
            try {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("anchor", context.getClassName() + "." + context.getMethodName());
                metadata.put("reviewMode", context.getReviewMode().name());
                metadata.put("totalSignatures", context.getChainMetadata() != null
                        ? context.getChainMetadata().get("totalSignatures") : 0);

                parsed = resultParser.parse(llmResponse.content(), metadata, context.getReviewMode());
            } catch (Exception e) {
                log.warn("Parse failed on attempt {}/{}: {}", attempt + 1, MAX_RETRIES + 1, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    parsed = buildFallbackResponse("解析失败: " + e.getMessage());
                    break;
                }
                retrySteps.add(Map.of("attempt", attempt + 1, "error", e.getMessage(), "phase", "parse"));
                continue;
            }

            // Validate schema
            try {
                schemaValidator.validate(parsed);
                log.info("Schema validation passed on attempt {}/{}", attempt + 1, MAX_RETRIES + 1);
                break; // 校验通过 → 退出循环
            } catch (SchemaValidationException e) {
                log.warn("Schema validation failed on attempt {}/{}: {}", attempt + 1, MAX_RETRIES + 1, e.getMessage());
                retrySteps.add(Map.of(
                        "attempt", attempt + 1,
                        "error", e.getMessage(),
                        "phase", "validation",
                        "tokens", llmResponse != null ? llmResponse.promptTokens() + llmResponse.completionTokens() : 0
                ));

                if (attempt == MAX_RETRIES) {
                    // 重试耗尽 → 构造诊断 response
                    parsed = buildFallbackResponse("格式校验失败: " + e.getMessage());
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Build result
        Map<String, Object> llmDetail = new LinkedHashMap<>();
        llmDetail.put("provider", llmConfig.getProvider());
        llmDetail.put("model", llmConfig.getModel());
        if (llmResponse != null) {
            llmDetail.put("tokens", llmResponse.promptTokens() + llmResponse.completionTokens());
        }

        List<SkillExecutionPlan.ExecutionStep> steps = new ArrayList<>();
        steps.add(new SkillExecutionPlan.ExecutionStep(1, "组装 Prompt",
                "System + User prompt merged with context",
                Map.of("systemPromptLength", systemPrompt.length(), "userPromptLength", currentUserPrompt.length())));

        int callCount = retrySteps.isEmpty() ? 1 : retrySteps.size() + 1;
        steps.add(new SkillExecutionPlan.ExecutionStep(2, "调用 LLM（" + callCount + " 次）",
                llmConfig.getProvider() + " / " + llmConfig.getModel(),
                llmDetail));

        if (!retrySteps.isEmpty()) {
            steps.add(new SkillExecutionPlan.ExecutionStep(3, "重试详情",
                    "共 " + retrySteps.size() + " 次重试",
                    Map.of("retries", retrySteps)));
        }

        steps.add(new SkillExecutionPlan.ExecutionStep(4, "解析输出",
                "Output parser: " + skill.getOutputParser(),
                Map.of("outputParser", skill.getOutputParser())));

        return new SkillResult(
                skillId,
                skill.getName(),
                true,
                null,
                estimatedTokens,
                llmResponse != null ? llmResponse.promptTokens() : 0,
                llmResponse != null ? llmResponse.completionTokens() : 0,
                (int) duration,
                parsed,
                systemPrompt,
                outputSchema,
                steps
        );
    }

    /**
     * 构造重试耗尽时的兜底 ReviewResponse。
     */
    private ReviewResponse buildFallbackResponse(String errorDetail) {
        ReviewResponse response = new ReviewResponse();
        // status=PARSING_FAILED：标记 LLM 输出格式连续校验失败，审查结果为空
        response.setSummary(new ReviewSummary(0, 0, 0, 0, "WARNING", "PARSING_FAILED"));
        response.setRequirementChecks(List.of());

        CodeIssue diag = new CodeIssue();
        diag.setSeverity("WARNING");
        diag.setCategory("SYSTEM");
        diag.setDescription("LLM 输出格式连续 " + (MAX_RETRIES + 1) + " 次校验失败，审查结果为空。"
                + "最后一次失败原因：" + errorDetail);
        diag.setSuggestion("请重试审查，或检查 LLM 配置是否正常");
        response.setIssues(List.of(diag));

        return response;
    }

    /**
     * Generate execution plan without calling LLM (preview).
     */
    public SkillExecutionPlan plan(Skill skill, SkillContext context) {
        SkillValidator.ValidationResult validation = skillValidator.validate(skill);

        String systemPrompt = promptBuilder.getSystemPrompt(skill);
        String userPrompt = promptBuilder.build(skill, context.getProjectKnowledge(),
                context.getChainMetadata(), context.getRequirements(), context.getSourceCode(),
                context.getReviewMode(), context.getDiffContext());
        String outputSchema = promptBuilder.getOutputSchema(skill);

        int estimatedTokens = (systemPrompt.length() + userPrompt.length()) / 4;
        int totalChars = systemPrompt.length() + userPrompt.length();

        ModelConfig model = modelRegistry.select(skill.getPreferredProvider(), "general");

        return new SkillExecutionPlan(
                skill.getId(), skill.getName(),
                List.of(
                        new SkillExecutionPlan.ExecutionStep(1, "加载模板",
                                "Prompt template: " + skill.getPromptTemplateId(),
                                Map.of("templateId", skill.getPromptTemplateId())),
                        new SkillExecutionPlan.ExecutionStep(2, "组装 Prompt",
                                "System + User prompt (" + totalChars + " chars)",
                                Map.of("estimatedTokens", estimatedTokens)),
                        new SkillExecutionPlan.ExecutionStep(3, "调用 LLM（含自动重试）",
                                skill.getPreferredProvider() + " / " + (model != null ? model.getModelId() : "unknown"),
                                Map.of("provider", skill.getPreferredProvider())),
                        new SkillExecutionPlan.ExecutionStep(4, "解析 + 校验",
                                "Output parser: " + skill.getOutputParser() + " + SchemaValidator",
                                Map.of("outputSchema", outputSchema))
                ),
                skill.getPromptTemplateId(),
                skill.getPreferredProvider(),
                model != null ? model.getModelId() : "unknown",
                estimatedTokens, totalChars,
                context.getSourceCode().size(),
                context.getChainMetadata().get("totalSignatures") instanceof Number n ? n.intValue() : 0,
                validation.valid(), validation.warnings(), validation.errors(),
                systemPrompt.substring(0, Math.min(300, systemPrompt.length())),
                outputSchema
        );
    }
}
