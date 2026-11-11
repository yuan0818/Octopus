package io.yuan.octopus.review.service;

import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.dto.ReviewMode;
import io.yuan.octopus.review.prompt.PromptManager;
import io.yuan.octopus.review.prompt.PromptTemplate;
import io.yuan.octopus.review.skill.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Assembles the full LLM prompt using PromptManager templates.
 * Supports multiple Skills (code review, security review, etc.).
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private final PromptManager promptManager;

    public PromptBuilder(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    /**
     * Build the system prompt for the given skill.
     */
    public String getSystemPrompt(Skill skill) {
        PromptTemplate template = promptManager.get(skill.getPromptTemplateId());
        String system = skill.enrichSystemPrompt(template.systemPrompt());

        // Append output schema as format instruction
        if (template.outputSchema() != null && !template.outputSchema().isBlank()) {
            system += "\n\n输出必须使用以下 JSON 格式，不要包含其他解释性文字：\n" + template.outputSchema();
        }
        return system;
    }

    /**
     * Build the user prompt with variable injection.
     */
    public String build(Skill skill, String projectContext,
                        Map<String, Object> chainMetadata,
                        List<RequirementItem> requirements,
                        Map<String, String> sourceCode) {
        return build(skill, projectContext, chainMetadata, requirements, sourceCode, ReviewMode.REQUIREMENT_AWARE);
    }

    public String build(Skill skill, String projectContext,
                        Map<String, Object> chainMetadata,
                        List<RequirementItem> requirements,
                        Map<String, String> sourceCode,
                        ReviewMode reviewMode) {
        return build(skill, projectContext, chainMetadata, requirements, sourceCode, reviewMode, null);
    }

    /**
     * @param diffContext 链路版本对比摘要（entry-chain-diff 的输出，可选）；
     *                    非空时注入 Diff Context 段落，作为 issue.diffEvidence 的事实来源
     */
    public String build(Skill skill, String projectContext,
                        Map<String, Object> chainMetadata,
                        List<RequirementItem> requirements,
                        Map<String, String> sourceCode,
                        ReviewMode reviewMode,
                        String diffContext) {
        PromptTemplate template = promptManager.get(skill.getPromptTemplateId());

        // Prepare variables
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("anchor", chainMetadata.getOrDefault("anchor", "unknown"));
        variables.put("reviewMode", reviewMode.name());
        variables.put("reviewModeInstructions", reviewModeInstructions(reviewMode));
        variables.put("projectContext", projectContext != null ? projectContext : "");
        variables.put("requirements", formatRequirements(requirements, reviewMode));
        variables.put("sourceCode", formatSourceCode(sourceCode));

        String prompt = template.renderUserPrompt(variables);
        if (diffContext != null && !diffContext.isBlank()) {
            prompt = "## Diff Context（本次链路版本对比）\n" + diffContext.strip()
                    + "\n\n以上 diff 是该入口在两个版本间的链路变更，引用时作为 diffEvidence。\n\n" + prompt;
        }
        if (!prompt.contains(reviewMode.name())) {
            prompt = "## Review Mode\n" + reviewMode.name() + "\n\n"
                    + reviewModeInstructions(reviewMode) + "\n\n" + prompt;
        }
        return prompt;
    }

    /**
     * Build output format constraint string from template schema.
     */
    public String getOutputSchema(Skill skill) {
        PromptTemplate template = promptManager.get(skill.getPromptTemplateId());
        return template.outputSchema();
    }

    private String formatRequirements(List<RequirementItem> requirements, ReviewMode reviewMode) {
        if (requirements == null || requirements.isEmpty()) {
            return reviewMode == ReviewMode.RISK_BASED
                    ? "无明确需求。本次只进行入口链路内可证明的高价值风险审查，不判断需求覆盖。"
                    : "无";
        }
        return requirements.stream()
                .map(r -> "- " + r.getId() + ": " + r.getTitle() + " — " + r.getDescription())
                .collect(Collectors.joining("\n"));
    }

    private String reviewModeInstructions(ReviewMode reviewMode) {
        if (reviewMode == ReviewMode.RISK_BASED) {
            return """
                    当前是 RISK_BASED（无需求）入口链路审查：
                    - 只根据当前入口调用链、还原源码和可见上下文输出可证明的高价值风险。
                    - 禁止输出 REQUIREMENT_MISMATCH，禁止判断“需求未满足”。
                    - 不做架构治理、数据安全治理、全局鉴权治理、日志体系治理、模块边界治理。
                    - 只能对当前链路可见证据负责；缺少全局上下文时降低 confidence 或不输出。
                    - 可关注明显空值/异常吞掉/成功包装失败/事务部分成功/幂等并发/状态流转矛盾/返回语义等链路内风险。
                    """;
        }
        return """
                当前是 REQUIREMENT_AWARE（有需求）入口链路审查：
                - 以需求、验收标准和业务规则为主线判断代码是否满足要求。
                - 可以输出 REQUIREMENT_MISMATCH / REQUIREMENT_AMBIGUITY。
                - 其它风险只作为补充，必须和该入口行为或需求实现直接相关。
                - 不做脱离当前入口链路证据范围的架构治理或全局安全治理。
                """;
    }

    private String formatSourceCode(Map<String, String> sourceCode) {
        if (sourceCode == null || sourceCode.isEmpty()) return "无源码";
        // 不带语言扩展名：语言由模型从代码内容自行识别，prompt 保持语言中立
        return sourceCode.entrySet().stream()
                .map(entry -> "--- " + entry.getKey() + " ---\n" + entry.getValue())
                .collect(Collectors.joining("\n\n"));
    }
}
