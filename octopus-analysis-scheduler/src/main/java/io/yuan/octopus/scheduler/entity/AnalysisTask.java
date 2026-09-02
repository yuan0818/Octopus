package io.yuan.octopus.scheduler.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_task", indexes = {
    @Index(name = "idx_task_status", columnList = "status"),
    @Index(name = "idx_task_created", columnList = "created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AnalysisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_config_id")
    private Long repoConfigId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(length = 200)
    private String branch;

    @Column(name = "commit_id", length = 100)
    private String commitId;

    @Column(length = 200)
    private String tag;

    @Column(length = 20, nullable = false)
    private String language = "JAVA";

    @Column(name = "analysis_type", length = 20, nullable = false)
    private String analysisType = "FULL";

    @Column(length = 20, nullable = false)
    private String status = "PENDING";

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "max_retries")
    private int maxRetries = 3;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "queue_name", length = 100)
    private String queueName;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "timeout_seconds")
    private int timeoutSeconds = 1800;

    @Column(name = "created_at")
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
}
