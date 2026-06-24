package io.yuan.octopus.review.skill;

import java.util.List;
import java.util.Map;

/**
 * Describes what will happen when a Skill executes.
 * Returned by the preview API so users can see the full execution plan
 * before actually running it.
 */
public class SkillExecutionPlan {

    private final String skillId;
    private final String skillName;

    // Pipeline steps
    private final List<ExecutionStep> steps;

    // Resources
    private final String promptTemplateId;
    private final String provider;
    private final String modelId;
    private final int estimatedTokens;
    private final int totalChars;
    private final int classCount;
    private final int methodCount;

    // Validation
    private final boolean valid;
    private final List<String> warnings;
    private final List<String> errors;

    // Preview (samples)
    private final String systemPromptPreview;
    private final String outputSchema;

    public SkillExecutionPlan(String skillId, String skillName, List<ExecutionStep> steps,
                               String promptTemplateId, String provider, String modelId,
                               int estimatedTokens, int totalChars, int classCount, int methodCount,
                               boolean valid, List<String> warnings, List<String> errors,
                               String systemPromptPreview, String outputSchema) {
        this.skillId = skillId;
        this.skillName = skillName;
        this.steps = steps;
        this.promptTemplateId = promptTemplateId;
        this.provider = provider;
        this.modelId = modelId;
        this.estimatedTokens = estimatedTokens;
        this.totalChars = totalChars;
        this.classCount = classCount;
        this.methodCount = methodCount;
        this.valid = valid;
        this.warnings = warnings;
        this.errors = errors;
        this.systemPromptPreview = systemPromptPreview;
        this.outputSchema = outputSchema;
    }

    public record ExecutionStep(int order, String name, String description, Map<String, Object> detail) {}

    public String getSkillId() { return skillId; }
    public String getSkillName() { return skillName; }
    public List<ExecutionStep> getSteps() { return steps; }
    public String getPromptTemplateId() { return promptTemplateId; }
    public String getProvider() { return provider; }
    public String getModelId() { return modelId; }
    public int getEstimatedTokens() { return estimatedTokens; }
    public int getTotalChars() { return totalChars; }
    public int getClassCount() { return classCount; }
    public int getMethodCount() { return methodCount; }
    public boolean isValid() { return valid; }
    public List<String> getWarnings() { return warnings; }
    public List<String> getErrors() { return errors; }
    public String getSystemPromptPreview() { return systemPromptPreview; }
    public String getOutputSchema() { return outputSchema; }
}
