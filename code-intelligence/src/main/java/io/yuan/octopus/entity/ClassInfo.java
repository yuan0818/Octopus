package io.yuan.octopus.entity;

import io.yuan.octopus.common.ClassType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 类元数据实体——按 (project_id, branch, commit_id) 版本化存储。
 * <p>
 * 查询默认走 {@code is_head = true}（HEAD 版本）。
 * 指定 {@code commit_id} 时精确匹配历史版本。
 */
@Entity
@Table(name = "class_info", indexes = {
    @Index(name = "idx_class_qualified_name", columnList = "qualified_name"),
    @Index(name = "idx_class_head", columnList = "project_id, branch, is_head"),
    @Index(name = "idx_class_commit", columnList = "project_id, branch, commit_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    /** 所属 commit hash。缺省 = HEAD（is_head=true）。 */
    @Column(name = "commit_id", length = 100)
    private String commitId;

    /** 是否该分支 HEAD 版本。同一 (project_id, branch) 下仅一条为 true。 */
    @Column(name = "is_head")
    private Boolean isHead;

    /** Branch this class record belongs to (mirrors {@code AnalyzedProjectEntity.branch}). */
    @Column(name = "branch", length = 200)
    private String branch;

    @Column(name = "package_id")
    private Long packageId;

    @Column(name = "qualified_name", length = 500)
    private String qualifiedName;

    @Column(name = "simple_name", length = 200)
    private String simpleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "class_type", length = 20)
    private ClassType classType;

    @Column(name = "modifiers", length = 100)
    private String modifiers;

    @Column(name = "source_file_path", length = 500)
    private String sourceFilePath;

    @Column(name = "parent_class", length = 500)
    private String parentClass;

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
