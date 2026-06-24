package io.yuan.octopus.review.skill;

import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.prompt.PromptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the integrity of a Skill definition.
 * Checks that all referenced components exist and are properly configured.
 */
@Component
public class SkillValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillValidator.class);

    private final PromptManager promptManager;
    private final ModelRegistry modelRegistry;

    public SkillValidator(PromptManager promptManager, ModelRegistry modelRegistry) {
        this.promptManager = promptManager;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Validate a skill's configuration.
     * Returns errors (blocking) and warnings (advisory).
     */
    public ValidationResult validate(Skill skill) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Validate prompt template exists
        if (skill.getPromptTemplateId() == null || skill.getPromptTemplateId().isBlank()) {
            errors.add("promptTemplateId is required");
        } else {
            try {
                promptManager.get(skill.getPromptTemplateId());
            } catch (IllegalArgumentException e) {
                errors.add("Prompt template '" + skill.getPromptTemplateId() + "' not found");
            }
        }

        // 2. Validate provider/model
        if (skill.getPreferredProvider() == null || skill.getPreferredProvider().isBlank()) {
            errors.add("preferredProvider is required");
        } else {
            var model = modelRegistry.select(skill.getPreferredProvider(), "general");
            if (model == null) {
                warnings.add("No model registered for provider '" + skill.getPreferredProvider()
                        + "'. Register one in Model Management.");
            } else if (!model.isEnabled()) {
                warnings.add("Model '" + model.getName() + "' is disabled.");
            }
        }

        // 3. Validate API Key
        if (skill.getPreferredProvider() != null) {
            String apiKey = modelRegistry.getApiKey(skill.getPreferredProvider());
            if (apiKey == null || apiKey.isBlank()) {
                errors.add("API key for '" + skill.getPreferredProvider() + "' is not configured.");
            }
        }

        // 4. Validate output parser
        if (skill.getOutputParser() == null || skill.getOutputParser().isBlank()) {
            warnings.add("No output parser specified. Using default parser.");
        }

        // 5. Validate maxTokens
        if (skill.getMaxTokens() <= 0) {
            errors.add("maxTokens must be greater than 0");
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {}
}
