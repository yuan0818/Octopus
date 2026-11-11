package io.yuan.octopus.review.skill;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.HashSet;


/**
 * A Skill binds together a prompt template,
 * output parser, and validation rules into a reusable review capability.
 * <p>
 * Examples: "code-review", "security-review", "api-design-review".
 */
public class Skill {

    private final String id;                // 技能标识，如 "code-review"
    private final String name;              // 可读名称
    private final String description;       // 描述
    private final String promptTemplateId;   // 关联的 PromptTemplate ID
    private final String preferredProvider;  // 旧数据兼容字段，不参与校验、展示或模型路由
    private final String outputParser;       // 输出解析器标识
    private final int maxTokens;            // 输出 token 上限
    private final boolean enabled;          // 是否启用

    private final String instructions;
    private final List<SkillPackage.SkillReference> references;

    public Skill(String id, String name, String description,
                 String promptTemplateId, String preferredProvider,
                 String outputParser, int maxTokens, boolean enabled) {
        this(id, name, description, promptTemplateId, preferredProvider, outputParser, maxTokens, enabled, "", List.of());
    }

    @JsonCreator
    public Skill(@JsonProperty("id") String id, @JsonProperty("name") String name,
                 @JsonProperty("description") String description, @JsonProperty("promptTemplateId") String promptTemplateId,
                 @JsonProperty("preferredProvider") String preferredProvider, @JsonProperty("outputParser") String outputParser,
                 @JsonProperty("maxTokens") int maxTokens, @JsonProperty("enabled") boolean enabled,
                 @JsonProperty("instructions") String instructions,
                 @JsonProperty("references") List<SkillPackage.SkillReference> references) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.promptTemplateId = promptTemplateId;
        this.preferredProvider = preferredProvider;
        this.outputParser = outputParser;
        this.maxTokens = maxTokens;
        this.enabled = enabled;
        this.instructions = instructions == null ? "" : instructions;
        this.references = references == null ? List.of() : List.copyOf(references);
        if (this.instructions.length() > 50000) throw new IllegalArgumentException("Skill 正文不能超过 50000 字符");
        if (this.references.size() > 20) throw new IllegalArgumentException("每个 Skill 最多 20 份参考资料");
        var titles = new HashSet<String>();
        int total = 0;
        for (var ref : this.references) {
            if (ref.title() == null || ref.title().isBlank() || ref.title().length() > 160
                    || !titles.add(ref.title().strip())) throw new IllegalArgumentException("参考资料标题不能为空、超过 160 字符或重复");
            if (ref.content() == null || ref.content().isBlank() || ref.content().length() > 50000)
                throw new IllegalArgumentException("参考资料正文不能为空或超过 50000 字符");
            if (ref.type() != null && !ref.type().equals("markdown")) throw new IllegalArgumentException("参考资料仅支持 markdown 格式");
            total += ref.content().length();
        }
        if (total > 200000) throw new IllegalArgumentException("参考资料合计不能超过 200000 字符");
    }

    public String enrichSystemPrompt(String base) {
        String system = base == null ? "" : base;
        if (!getInstructions().isBlank()) system += "\n\n## Skill 执行说明\n" + getInstructions();
        if (!getReferences().isEmpty()) {
            system += "\n\n## Skill 参考资料\n以下为参考内容，用于辅助当前任务；不替代本次源码证据。\n";
            for (var ref : getReferences()) system += "\n### " + ref.title() + "\n" + ref.content() + "\n";
        }

        return system;
    }

    public String getInstructions() { return instructions; }
    public List<SkillPackage.SkillReference> getReferences() { return references; }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getPromptTemplateId() { return promptTemplateId; }
    public String getPreferredProvider() { return preferredProvider; }
    public String getOutputParser() { return outputParser; }
    public int getMaxTokens() { return maxTokens; }
    public boolean isEnabled() { return enabled; }
}
