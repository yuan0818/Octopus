package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalysisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_config_id")
    private Long repoConfigId;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "current_step", length = 50)
    private String currentStep;

    @Column(name = "commit_id", length = 100)
    private String commitId;

    /** Git tag that this analysis was triggered for (e.g. {@code v1.2.0}). */
    @Column(name = "tag", length = 200)
    private String tag;

    /** Git branch this analysis targets (e.g. {@code main}, {@code release/eu}). */
    @Column(name = "branch", length = 200)
    private String branch;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

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
