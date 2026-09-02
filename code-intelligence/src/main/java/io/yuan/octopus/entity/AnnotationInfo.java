package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 注解信息实体——按 (project_id, branch, commit_id) 版本化存储。
 */
@Entity
@Table(name = "annotation_info", indexes = {
    @Index(name = "idx_annotation_head", columnList = "project_id, branch, is_head"),
    @Index(name = "idx_annotation_commit", columnList = "project_id, branch, commit_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnotationInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 ID（冗余，加速查询和清理）。 */
    @Column(name = "project_id")
    private Long projectId;

    /** 所属分支（冗余，加速查询和清理）。 */
    @Column(name = "branch", length = 200)
    private String branch;

    /** 所属 commit hash。缺省 = HEAD（is_head=true）。 */
    @Column(name = "commit_id", length = 100)
    private String commitId;

    /** 是否该分支 HEAD 版本。同一 (project_id, branch) 下仅一条为 true。 */
    @Column(name = "is_head")
    private Boolean isHead;

    @Column(name = "annotation_name", length = 200)
    private String annotationName;

    @Column(name = "annotation_params", columnDefinition = "TEXT")
    private String annotationParams;

    @Column(name = "target_type", length = 20)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** created_at 由 JPA 统一补齐，保证 VersionCleanupService 的保留期计算可用。 */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
