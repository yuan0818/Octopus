package io.yuan.octopus.review.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 审查追问请求（混合形态 review 的对话层）：
 * 首轮结构化审查完成后，reviewer 基于同一链路上下文继续提问。
 */
public class ChatRequest {

    /** 追问内容。 */
    @NotBlank
    private String question;

    /** 对话线程 id；为空则后端生成。同一审查的追问共享一个线程。 */
    private String threadId;

    private Long projectId;
    private String className;
    private String methodName;
    private String strategy = "BFS";
    private String branch;
    private String commitId;

    /** 模型路由：优先 modelName，未选择时使用独立的可用模型配置。 */
    private String skillId;
    private String modelName;

    /** 审查结论摘要（JSON 文本，前端传入它持有的最近一次审查结果）。 */
    private String reviewResult;

    /** 链路版本对比摘要（entry-chain-diff 输出，可选）。 */
    private String diffContext;

    // ── Getters & Setters ──

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getReviewResult() { return reviewResult; }
    public void setReviewResult(String reviewResult) { this.reviewResult = reviewResult; }

    public String getDiffContext() { return diffContext; }
    public void setDiffContext(String diffContext) { this.diffContext = diffContext; }
}
