package io.yuan.octopus.changereview.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 变更触发审查任务——独立于冲突的 {@code analysis_task} 表（ADR-004）。
 * 承载 Gerrit Change patchset 触发的审查流水线全生命周期状态。
 */
@Entity
@Table(name = "change_review_task", indexes = {
    @Index(name = "idx_crt_status", columnList = "status"),
    @Index(name = "idx_crt_change", columnList = "repo_config_id, change_number"),
    @Index(name = "idx_crt_running", columnList = "status, current_phase")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangeReviewTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_config_id", nullable = false)
    private Long repoConfigId;

    @Column(name = "platform", nullable = false, length = 20)
    @Builder.Default
    private String platform = "GERRIT";   // GERRIT / GITLAB(预留)

    @Column(name = "change_number", nullable = false, length = 50)
    private String changeNumber;

    @Column(name = "patchset_number", nullable = false)
    private Integer patchsetNumber;

    @Column(name = "target_branch", length = 200)
    private String targetBranch;

    @Column(name = "base_commit_id", length = 100)
    private String baseCommitId;

    @Column(name = "patchset_commit_id", length = 100)
    private String patchsetCommitId;

    /** 合并 merge commit SHA，即本次分析/review 的 commitId（ADR-006）。 */
    @Column(name = "merge_commit_id", length = 100)
    private String mergeCommitId;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";   // PENDING/RUNNING/COMPLETED/FAILED/PARTIALLY_COMPLETED/SUPERSEDED

    @Column(name = "failure_type", length = 30)
    private String failureType;          // FETCH_FAILED/MERGE_FAILED/MERGE_CONFLICT/ANALYSIS_FAILED/REVIEW_FAILED/CALLBACK_FAILED

    @Column(name = "trigger_source", nullable = false, length = 20)
    @Builder.Default
    private String triggerSource = "WEBHOOK";   // WEBHOOK/MANUAL

    /** 阶段机：崩溃后续跑依据（FETCH_MERGE/ANALYZE/DETECT_CHANGE/IMPACT/REVIEW/WRITEBACK）。 */
    @Column(name = "current_phase", length = 30)
    private String currentPhase;

    @Column(name = "affected_endpoint_count")
    @Builder.Default
    private Integer affectedEndpointCount = 0;

    @Column(name = "review_session_ids", columnDefinition = "JSON")
    private String reviewSessionIds;

    /**
     * REVIEW 阶段产物（按端点签名 → EndpointReviewResult 的 JSON）。
     * WRITEBACK 失败重试（/rewrite）时直接复用，避免重跑 LLM 审查。
     */
    @Column(name = "review_results", columnDefinition = "JSON")
    private String reviewResults;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

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
