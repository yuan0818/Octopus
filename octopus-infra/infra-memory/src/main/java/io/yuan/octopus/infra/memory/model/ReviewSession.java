package io.yuan.octopus.infra.memory.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 审查会话——完整记录一次审查的输入、输出和元信息。
 */
@Entity
@Table(name = "review_session", indexes = {
    @Index(name = "idx_project", columnList = "project_id, created_at"),
    @Index(name = "idx_anchor", columnList = "project_id, anchor_class, anchor_method")
})
public class ReviewSession {

    @Id
    @Column(name = "id", length = 100)
    private String id;

    @Column(name = "project_id", nullable = false, length = 100)
    private String projectId;

    @Column(name = "anchor_class", nullable = false, length = 500)
    private String anchorClass;

    @Column(name = "anchor_method", nullable = false, length = 500)
    private String anchorMethod;

    @Column(name = "strategy", length = 10)
    private String strategy = "BFS";

    @Column(name = "commit_id", length = 100)
    private String commitId;

    @Column(name = "tag", length = 200)
    private String tag;

    @Column(name = "requirements", columnDefinition = "JSON")
    private String requirements;

    @Column(name = "chain_classes", columnDefinition = "JSON")
    private String chainClasses;

    @Column(name = "full_report", columnDefinition = "JSON")
    private String fullReport;

    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "prompt_tokens")
    private int promptTokens;

    @Column(name = "completion_tokens")
    private int completionTokens;

    @Column(name = "total_tokens")
    private int totalTokens;

    @Column(name = "duration_ms")
    private int durationMs;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "status", length = 20)
    private String status = "COMPLETED";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Getters & Setters ──

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getAnchorClass() { return anchorClass; }
    public void setAnchorClass(String anchorClass) { this.anchorClass = anchorClass; }

    public String getAnchorMethod() { return anchorMethod; }
    public void setAnchorMethod(String anchorMethod) { this.anchorMethod = anchorMethod; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public String getRequirements() { return requirements; }
    public void setRequirements(String requirements) { this.requirements = requirements; }

    public String getChainClasses() { return chainClasses; }
    public void setChainClasses(String chainClasses) { this.chainClasses = chainClasses; }

    public String getFullReport() { return fullReport; }
    public void setFullReport(String fullReport) { this.fullReport = fullReport; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
