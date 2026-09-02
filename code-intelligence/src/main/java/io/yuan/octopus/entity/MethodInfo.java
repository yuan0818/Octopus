package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 方法元数据实体——按 (class_id, branch, commit_id) 版本化存储。
 * <p>
 * 源码获取优先通过 {@code commit_id} + {@code file_path} 使用 JGit git show；
 * MySQL {@code source_code} 字段保留过渡期作为兜底缓存。
 * <p>
 * {@code line_start} / {@code line_end} 在 PSI 分析时写入，用于按行号提取方法体。
 */
@Entity
@Table(name = "method_info", indexes = {
    @Index(name = "idx_method_signature", columnList = "method_signature")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MethodInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id", nullable = false)
    private ClassInfo classInfo;

    /** 所属 commit hash。缺省 = HEAD（is_head=true）。 */
    @Column(name = "commit_id", length = 100)
    private String commitId;

    /** 是否该分支 HEAD 版本。同一 (project_id, branch) 下仅一条为 true。 */
    @Column(name = "is_head")
    private Boolean isHead;

    @Column(name = "method_signature", length = 750)
    private String methodSignature;

    @Column(name = "method_name", length = 200)
    private String methodName;

    @Column(name = "parameter_types", columnDefinition = "TEXT")
    private String parameterTypes;

    @Column(name = "return_type", length = 200)
    private String returnType;

    @Column(name = "modifiers", length = 100)
    private String modifiers;

    /** 方法声明行号（从 1 计数）。用于 JGit 按行提取方法体。 */
    @Column(name = "line_start")
    private Integer lineStart;

    /** 方法体结束行号（从 1 计数）。用于 JGit 按行提取方法体。 */
    @Column(name = "line_end")
    private Integer lineEnd;

    /** 相对仓库根的路径（冗余加速 JGit 查询，也可通过 class_info.source_file_path 获取）。 */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /** 源码缓存（保留过渡期，git show 不可用时的兜底）。 */
    @Column(name = "source_code", columnDefinition = "MEDIUMTEXT")
    private String sourceCode;

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
