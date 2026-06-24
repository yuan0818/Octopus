package io.yuan.octopus.review.skill;

import io.yuan.octopus.review.model.ModelConfig;

/**
 * A Skill binds together a prompt template, model routing strategy,
 * output parser, and validation rules into a reusable review capability.
 * <p>
 * Examples: "code-review", "security-review", "api-design-review".
 */
public class Skill {

    private final String id;                // 技能标识，如 "code-review"
    private final String name;              // 可读名称
    private final String description;       // 描述
    private final String promptTemplateId;   // 关联的 PromptTemplate ID
    private final String preferredProvider;  // 首选模型提供商，如 "claude"
    private final String outputParser;       // 输出解析器标识
    private final int maxTokens;            // 输出 token 上限
    private final boolean enabled;          // 是否启用

    public Skill(String id, String name, String description,
                 String promptTemplateId, String preferredProvider,
                 String outputParser, int maxTokens, boolean enabled) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.promptTemplateId = promptTemplateId;
        this.preferredProvider = preferredProvider;
        this.outputParser = outputParser;
        this.maxTokens = maxTokens;
        this.enabled = enabled;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getPromptTemplateId() { return promptTemplateId; }
    public String getPreferredProvider() { return preferredProvider; }
    public String getOutputParser() { return outputParser; }
    public int getMaxTokens() { return maxTokens; }
    public boolean isEnabled() { return enabled; }
}
