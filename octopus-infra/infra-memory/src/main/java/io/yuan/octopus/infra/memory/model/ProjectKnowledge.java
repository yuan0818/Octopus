package io.yuan.octopus.infra.memory.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 项目级知识——架构决策、编码规范、设计模式等。
 * 每次审查完成后自动提取或人工录入，通过置信度体系管理生命周期。
 */
@Entity
@Table(name = "project_knowledge", indexes = {
    @Index(name = "idx_project_type", columnList = "project_id, status, knowledge_type"),
    @Index(name = "idx_project_status", columnList = "project_id, status"),
    @Index(name = "idx_verified", columnList = "last_verified_at")
})
public class ProjectKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 100)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_type", nullable = false, length = 50)
    private KnowledgeType knowledgeType;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "confidence", nullable = false)
    private int confidence = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private KnowledgeStatus status = KnowledgeStatus.TENTATIVE;

    @Column(name = "evidence", columnDefinition = "JSON")
    private String evidence;

    @Column(name = "counter_examples", columnDefinition = "JSON")
    private String counterExamples;

    @Column(name = "source", nullable = false, length = 20)
    private String source = "REVIEW";

    @Column(name = "source_detail", length = 100)
    private String sourceDetail;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "last_verified_by", length = 100)
    private String lastVerifiedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Getters & Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public KnowledgeType getKnowledgeType() { return knowledgeType; }
    public void setKnowledgeType(KnowledgeType knowledgeType) { this.knowledgeType = knowledgeType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getConfidence() { return confidence; }
    public void setConfidence(int confidence) { this.confidence = confidence; }

    public KnowledgeStatus getStatus() { return status; }
    public void setStatus(KnowledgeStatus status) { this.status = status; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public String getCounterExamples() { return counterExamples; }
    public void setCounterExamples(String counterExamples) { this.counterExamples = counterExamples; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceDetail() { return sourceDetail; }
    public void setSourceDetail(String sourceDetail) { this.sourceDetail = sourceDetail; }

    public LocalDateTime getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(LocalDateTime lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }

    public String getLastVerifiedBy() { return lastVerifiedBy; }
    public void setLastVerifiedBy(String lastVerifiedBy) { this.lastVerifiedBy = lastVerifiedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
