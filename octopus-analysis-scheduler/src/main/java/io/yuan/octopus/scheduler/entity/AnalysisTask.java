package io.yuan.octopus.scheduler.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 调度追踪记录。
 * <p>
 * 表归属：本实体独占 {@code analysis_schedule_task}。此前与 code-intelligence 的
 * {@code AnalysisTask} 共同映射 {@code analysis_task}，两边 {@code ddl-auto: update}
 * 互相 ALTER（本侧 language/analysis_type 为 NOT NULL，code-intelligence 插入时不填，
 * MySQL strict mode 下直接失败）。{@code analysis_task} 现由 code-intelligence 独占。
 * 存量数据迁移见 {@code src/main/resources/db/manual/split_analysis_schedule_task.sql}。
 */
@Entity
@Table(name = "analysis_schedule_task", indexes = {
    @Index(name = "idx_sched_task_status", columnList = "status"),
    @Index(name = "idx_sched_task_created", columnList = "created_at")
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

    // @Builder.Default：否则 Lombok builder 忽略字段初始值，build() 出来是 null，违反 NOT NULL
    @Column(length = 20, nullable = false)
    @Builder.Default
    private String language = "JAVA";

    @Column(name = "analysis_type", length = 20, nullable = false)
    @Builder.Default
    private String analysisType = "FULL";

    @Column(length = 20, nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries")
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "queue_name", length = 100)
    private String queueName;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "timeout_seconds")
    @Builder.Default
    private Integer timeoutSeconds = 1800;

    // Older tasks written by code-intelligence have no scheduler settings.
    // Keep nullable storage compatible and apply defaults when reading them.
    public int getRetryCount() {
        return retryCount == null ? 0 : retryCount;
    }

    public int getMaxRetries() {
        return maxRetries == null ? 3 : maxRetries;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds == null ? 1800 : timeoutSeconds;
    }

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
