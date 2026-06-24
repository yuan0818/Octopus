package io.yuan.octopus.review.skill;

import io.yuan.octopus.review.adapter.LLMAdapter.LLMResponse;
import io.yuan.octopus.review.dto.LLMConfig;
import io.yuan.octopus.review.model.ModelConfig;
import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.service.LLMIntegrationService;
import io.yuan.octopus.review.service.PromptBuilder;
import io.yuan.octopus.review.service.ReviewResultParser;
import io.yuan.octopus.review.dto.ReviewResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a Skill's full pipeline:
 * 1. Load prompt template → 2. Assemble prompt with context
 * 3. Call LLM → 4. Parse result according to skill's output format
 */
@Component
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    private final PromptBuilder promptBuilder;
    private final LLMIntegrationService llmIntegration;
    private final ReviewResultParser resultParser;
    private final SkillValidator skillValidator;
    private final ModelRegistry modelRegistry;

    public SkillExecutor(PromptBuilder promptBuilder,
                         LLMIntegrationService llmIntegration,
                         ReviewResultParser resultParser,
                         SkillValidator skillValidator,
                         ModelRegistry modelRegistry) {
        this.promptBuilder = promptBuilder;
        this.llmIntegration = llmIntegration;
        this.resultParser = resultParser;
        this.skillValidator = skillValidator;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Execute a skill with the given context.
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
                context.getChainMetadata(), context.getRequirements(), context.getSourceCode(), context.getReviewMode());
        String outputSchema = promptBuilder.getOutputSchema(skill);

        int estimatedTokens = (systemPrompt.length() + userPrompt.length()) / 4;

        // Step 3: Resolve LLM config
        LLMConfig llmConfig = context.getLlmConfig() != null ? context.getLlmConfig() : new LLMConfig();
        if (llmConfig.getProvider() == null) {
            llmConfig.setProvider(skill.getPreferredProvider());
        }

        // Step 4: Call LLM
        LLMResponse llmResponse = llmIntegration.call(systemPrompt, userPrompt, llmConfig);

        // Step 5: Parse result using the parser determined by the skill
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("anchor", context.getClassName() + "." + context.getMethodName());
        metadata.put("reviewMode", context.getReviewMode().name());
        metadata.put("totalSignatures", context.getChainMetadata() != null
                ? context.getChainMetadata().get("totalSignatures") : 0);

        ReviewResponse parsed = resultParser.parse(llmResponse.content(), metadata, context.getReviewMode());

        long duration = System.currentTimeMillis() - startTime;

        // Step 6: Build result
        Map<String, Object> llmDetail = new LinkedHashMap<>();
        llmDetail.put("provider", llmConfig.getProvider());
        llmDetail.put("model", llmConfig.getModel());
        llmDetail.put("tokens", llmResponse.promptTokens() + llmResponse.completionTokens());

        return new SkillResult(
                skillId,
                skill.getName(),
                true,
                null,
                estimatedTokens,
                llmResponse.promptTokens(),
                llmResponse.completionTokens(),
                (int) duration,
                parsed,
                systemPrompt,
                outputSchema,
                List.of(
                        new SkillExecutionPlan.ExecutionStep(1, "组装 Prompt",
                                "System + User prompt merged with context",
                                Map.of("systemPromptLength", systemPrompt.length(), "userPromptLength", userPrompt.length())),
                        new SkillExecutionPlan.ExecutionStep(2, "调用 LLM",
                                llmConfig.getProvider() + " / " + llmConfig.getModel(),
                                llmDetail),
                        new SkillExecutionPlan.ExecutionStep(3, "解析输出",
                                "Output parser: " + skill.getOutputParser(),
                                Map.of("outputParser", skill.getOutputParser()))
                )
        );
    }

    /**
     * Generate execution plan without calling LLM (preview).
     */
    public SkillExecutionPlan plan(Skill skill, SkillContext context) {
        SkillValidator.ValidationResult validation = skillValidator.validate(skill);

        String systemPrompt = promptBuilder.getSystemPrompt(skill);
        String userPrompt = promptBuilder.build(skill, context.getProjectKnowledge(),
                context.getChainMetadata(), context.getRequirements(), context.getSourceCode(), context.getReviewMode());
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
                        new SkillExecutionPlan.ExecutionStep(3, "调用 LLM",
                                skill.getPreferredProvider() + " / " + (model != null ? model.getModelId() : "unknown"),
                                Map.of("provider", skill.getPreferredProvider())),
                        new SkillExecutionPlan.ExecutionStep(4, "解析输出",
                                "Output parser: " + skill.getOutputParser(),
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
