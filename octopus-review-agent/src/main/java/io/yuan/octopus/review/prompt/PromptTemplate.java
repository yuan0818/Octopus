package io.yuan.octopus.review.prompt;

import java.util.Map;

/**
 * A prompt template with named variables.
 * <p>
 * Template format: plain text with {{variable}} placeholders.
 * Example:
 * <pre>
 * ## Requirements to Verify
 * {{#each requirements}}
 * - {{id}}: {{title}} — {{description}}
 * {{/each}}
 * </pre>
 */
public record PromptTemplate(
        String id,          // 模板唯一标识，如 "review.code"
        String name,        // 可读名称
        String systemPrompt, // System message
        String userPrompt,  // User message template
        String outputSchema  // JSON Schema 输出格式约束
) {

    /**
     * Render the user prompt by replacing {{variable}} placeholders.
     */
    public String renderUserPrompt(Map<String, Object> variables) {
        String result = userPrompt;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(key, value);
        }
        return result;
    }
}
