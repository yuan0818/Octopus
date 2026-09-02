package io.yuan.octopus.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 变更总结请求：同一入口在两个分析版本之间的「修改要点 + 测试要点」。
 * 版本 = (branch, commitId) 对，commitId 为空表示该分支 HEAD 快照；
 * 两版本可以同分支不同 commit/tag，也可以完全不同分支。
 */
public class ChangeSummaryRequest {

    @NotNull
    private Long projectId;

    @NotBlank
    private String className;

    @NotBlank
    private String methodName;

    private String fromBranch;
    private String fromCommitId;
    private String toBranch;
    private String toCommitId;

    /** 模型路由（可选）：优先 modelName，回退 skill 的 preferredProvider。 */
    private String skillId;
    private String modelName;

    // ── Getters & Setters ──

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getFromBranch() { return fromBranch; }
    public void setFromBranch(String fromBranch) { this.fromBranch = fromBranch; }

    public String getFromCommitId() { return fromCommitId; }
    public void setFromCommitId(String fromCommitId) { this.fromCommitId = fromCommitId; }

    public String getToBranch() { return toBranch; }
    public void setToBranch(String toBranch) { this.toBranch = toBranch; }

    public String getToCommitId() { return toCommitId; }
    public void setToCommitId(String toCommitId) { this.toCommitId = toCommitId; }

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
}
