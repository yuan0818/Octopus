package io.yuan.octopus.review.skill;

import io.yuan.octopus.review.dto.ReviewResponse;

import java.util.List;

/**
 * The result of executing a Skill.
 */
public record SkillResult(
        String skillId,
        String skillName,
        boolean success,
        String errorMessage,
        int estimatedTokens,
        int promptTokens,
        int completionTokens,
        int durationMs,
        ReviewResponse reviewResponse,
        String assembledSystemPrompt,
        String outputSchema,
        List<SkillExecutionPlan.ExecutionStep> executionSteps
) {
    public static SkillResult failure(String skillId, String error) {
        return new SkillResult(skillId, null, false, error, 0, 0, 0, 0, null, null, null, List.of());
    }
}
