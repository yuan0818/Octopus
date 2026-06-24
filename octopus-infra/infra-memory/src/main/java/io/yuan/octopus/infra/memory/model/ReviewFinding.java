package io.yuan.octopus.infra.memory.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 审查发现——每次审查中每个需求检查结果和附加问题。
 */
@Entity
@Table(name = "review_finding", indexes = {
    @Index(name = "idx_project_class", columnList = "project_id, anchor_class"),
    @Index(name = "idx_project_req", columnList = "project_id, requirement_id"),
    @Index(name = "idx_severity", columnList = "project_id, severity"),
    @Index(name = "idx_session", columnList = "session_id")
})
public class ReviewFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 100)
    private String projectId;

    @Column(name = "anchor_class", nullable = false, length = 500)
    private String anchorClass;

    @Column(name = "anchor_method", nullable = false, length = 500)
    private String anchorMethod;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "requirement_id", length = 50)
    private String requirementId;

    @Column(name = "requirement_title", length = 200)
    private String requirementTitle;

    @Column(name = "passed")
    private Boolean passed;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity = "WARNING";

    @Column(name = "category", nullable = false, length = 50)
    private String category = "CONVENTION";

    @Column(name = "finding_text", nullable = false, columnDefinition = "TEXT")
    private String findingText;

    @Column(name = "suggestion", columnDefinition = "TEXT")
    private String suggestion;

    @Column(name = "code_references", columnDefinition = "JSON")
    private String codeReferences;

    @Column(name = "model_used", length = 50)
    private String modelUsed;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Getters & Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getAnchorClass() { return anchorClass; }
    public void setAnchorClass(String anchorClass) { this.anchorClass = anchorClass; }

    public String getAnchorMethod() { return anchorMethod; }
    public void setAnchorMethod(String anchorMethod) { this.anchorMethod = anchorMethod; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public String getRequirementTitle() { return requirementTitle; }
    public void setRequirementTitle(String requirementTitle) { this.requirementTitle = requirementTitle; }

    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getFindingText() { return findingText; }
    public void setFindingText(String findingText) { this.findingText = findingText; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public String getCodeReferences() { return codeReferences; }
    public void setCodeReferences(String codeReferences) { this.codeReferences = codeReferences; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
