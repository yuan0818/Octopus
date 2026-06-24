package io.yuan.octopus.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public class ReviewRequest {

    @NotNull
    private Long projectId;

    @NotBlank
    private String className;

    @NotBlank
    private String methodName;

    private String strategy = "BFS";

    private List<RequirementItem> requirements;

    private String docPath;

    private String mode = "SYNC";   // SYNC / ASYNC

    private String webhookUrl;

    private String skillId;         // 使用的 Skill ID，为空则用 default

    private String modelName;       // 使用的模型名称，从 ModelRegistry 中加载完整配置

    private String commitId;       // 分析时的 commit hash

    private String tag;             // Git tag（可选）

    private ReviewMode reviewMode;  // REQUIREMENT_AWARE / RISK_BASED；为空时按是否有需求自动推导

    private LLMConfig llmConfig;

    // ── Getters & Setters ──

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public List<RequirementItem> getRequirements() { return requirements; }
    public void setRequirements(List<RequirementItem> requirements) { this.requirements = requirements; }

    public String getDocPath() { return docPath; }
    public void setDocPath(String docPath) { this.docPath = docPath; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public LLMConfig getLlmConfig() { return llmConfig; }
    public void setLlmConfig(LLMConfig llmConfig) { this.llmConfig = llmConfig; }

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public ReviewMode getReviewMode() { return reviewMode; }
    public void setReviewMode(ReviewMode reviewMode) { this.reviewMode = reviewMode; }
}
