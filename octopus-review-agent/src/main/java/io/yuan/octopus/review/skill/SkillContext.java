package io.yuan.octopus.review.skill;

import io.yuan.octopus.review.dto.LLMConfig;
import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.dto.ReviewMode;

import java.util.List;
import java.util.Map;

/**
 * The full context passed into a Skill execution.
 * Contains everything the skill needs: source code, requirements, project knowledge.
 */
public class SkillContext {

    private final String projectId;
    private final String className;
    private final String methodName;
    private final String strategy;
    private final List<RequirementItem> requirements;
    private final String projectKnowledge;
    private final Map<String, Object> chainMetadata;
    private final Map<String, String> sourceCode;
    private final LLMConfig llmConfig;
    private final ReviewMode reviewMode;

    /** 链路版本对比摘要（可选）；注入 prompt 的 Diff Context 段落。 */
    private String diffContext;

    public SkillContext(String projectId, String className, String methodName,
                        String strategy, List<RequirementItem> requirements,
                        String projectKnowledge, Map<String, Object> chainMetadata,
                        Map<String, String> sourceCode, LLMConfig llmConfig) {
        this(projectId, className, methodName, strategy, requirements, projectKnowledge,
                chainMetadata, sourceCode, llmConfig, ReviewMode.REQUIREMENT_AWARE);
    }

    public SkillContext(String projectId, String className, String methodName,
                        String strategy, List<RequirementItem> requirements,
                        String projectKnowledge, Map<String, Object> chainMetadata,
                        Map<String, String> sourceCode, LLMConfig llmConfig,
                        ReviewMode reviewMode) {
        this.projectId = projectId;
        this.className = className;
        this.methodName = methodName;
        this.strategy = strategy;
        this.requirements = requirements;
        this.projectKnowledge = projectKnowledge;
        this.chainMetadata = chainMetadata;
        this.sourceCode = sourceCode;
        this.llmConfig = llmConfig;
        this.reviewMode = reviewMode;
    }

    public String getProjectId() { return projectId; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getStrategy() { return strategy; }
    public List<RequirementItem> getRequirements() { return requirements; }
    public String getProjectKnowledge() { return projectKnowledge; }
    public Map<String, Object> getChainMetadata() { return chainMetadata; }
    public Map<String, String> getSourceCode() { return sourceCode; }
    public LLMConfig getLlmConfig() { return llmConfig; }
    public ReviewMode getReviewMode() { return reviewMode; }
    public String getDiffContext() { return diffContext; }
    public void setDiffContext(String diffContext) { this.diffContext = diffContext; }
}
