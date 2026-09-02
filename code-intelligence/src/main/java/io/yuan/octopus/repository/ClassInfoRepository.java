package io.yuan.octopus.repository;

import io.yuan.octopus.entity.ClassInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ClassInfoRepository extends JpaRepository<ClassInfo, Long> {

    void deleteByProjectId(Long projectId);

    /**
     * 项目下已分析过的分支列表（按最近写入排序）。本地项目无 Git 服务可询，
     * 版本枚举以元数据为准——多分支数据按 branch 列共存于同一 project。
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT ci.branch FROM ClassInfo ci
            WHERE ci.projectId = :projectId
            GROUP BY ci.branch
            ORDER BY MAX(ci.id) DESC
            """)
    java.util.List<String> findAnalyzedBranches(@org.springframework.data.repository.query.Param("projectId") Long projectId);

    /** 指定分支下已分析过的 commit 列表（按最近写入排序）。 */
    @org.springframework.data.jpa.repository.Query("""
            SELECT ci.commitId FROM ClassInfo ci
            WHERE ci.projectId = :projectId AND ci.branch = :branch
            GROUP BY ci.commitId
            ORDER BY MAX(ci.id) DESC
            """)
    java.util.List<String> findAnalyzedCommitIds(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("branch") String branch);

    /** 指定分支当前 HEAD 版本的 commitId（is_head 行）。 */
    @org.springframework.data.jpa.repository.Query("""
            SELECT ci.commitId FROM ClassInfo ci
            WHERE ci.projectId = :projectId AND ci.branch = :branch AND ci.isHead = true
            """)
    java.util.List<String> findHeadCommitIds(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("branch") String branch);

    /** 项目 + 全限定名 + 指定 commit 的类行（链路 diff 按版本定位坐标用）。 */
    List<ClassInfo> findByProjectIdAndQualifiedNameAndCommitId(
            Long projectId, String qualifiedName, String commitId);

    /** 项目 + 全限定名的 HEAD 类行。 */
    List<ClassInfo> findByProjectIdAndQualifiedNameAndIsHeadTrue(Long projectId, String qualifiedName);

    /**
     * 按 qualified_name 取 HEAD 版本行（版本化后同名行会随分析累积，必须限定 is_head）。
     */
    List<ClassInfo> findByQualifiedNameAndIsHeadTrue(String qualifiedName);

    /** 按 qualified_name 取指定 commit 版本行。 */
    List<ClassInfo> findByQualifiedNameAndCommitId(String qualifiedName, String commitId);

    /** HEAD 版本前缀查询（包浏览）。 */
    List<ClassInfo> findByQualifiedNameStartingWithAndIsHeadTrue(String prefix);

    /**
     * Find all classes belonging to a project that were parsed from a specific source file.
     * Used for incremental (diff-level) metadata updates.
     */
    List<ClassInfo> findByProjectIdAndSourceFilePath(Long projectId, String sourceFilePath);

    void deleteByProjectIdAndBranch(Long projectId, String branch);

    List<ClassInfo> findByProjectId(Long projectId);

    List<ClassInfo> findByProjectIdAndBranchAndQualifiedNameContaining(Long projectId, String branch, String packageName);

    /**
     * Find all classes belonging to a project+branch that were parsed from a specific source file.
     * Used for branch-safe incremental metadata updates.
     */
    List<ClassInfo> findByProjectIdAndBranchAndSourceFilePath(Long projectId, String branch, String sourceFilePath);

    /**
     * 增量写入前收集该文件当前 HEAD 行（注解按 target 翻转、继承关系清理用）。
     */
    List<ClassInfo> findByProjectIdAndBranchAndSourceFilePathAndIsHeadTrue(
            Long projectId, String branch, String sourceFilePath);

    List<ClassInfo> findByProjectIdAndBranch(Long projectId, String branch);

    /**
     * HEAD 版本查询：commitId 为空时按 (project_id, branch, is_head=true) 取当前版本。
     */
    List<ClassInfo> findByProjectIdAndBranchAndIsHeadTrue(Long projectId, String branch);

    /**
     * 指定 commitId 版本查询。
     */
    List<ClassInfo> findByProjectIdAndBranchAndCommitId(Long projectId, String branch, String commitId);

    /**
     * 指定 commitId 版本 + 源文件路径查询（变更方法识别：按变更 .java 文件定位版本化类行）。
     */
    List<ClassInfo> findByProjectIdAndBranchAndCommitIdAndSourceFilePath(
            Long projectId, String branch, String commitId, String sourceFilePath);

    /**
     * 跨 project 按 commitId + 源文件路径查询（变更方法识别用——变更 .java 文件
     * 路径绝对唯一，无需先定位 project，projectId 由返回实体携带）。
     */
    List<ClassInfo> findByCommitIdAndSourceFilePath(String commitId, String sourceFilePath);

    /**
     * 全 project（不限 branch）的 HEAD 版本查询。
     */
    List<ClassInfo> findByProjectIdAndIsHeadTrue(Long projectId);

    /**
     * 全 project（不限 branch）的指定 commitId 版本查询。
     */
    List<ClassInfo> findByProjectIdAndCommitId(Long projectId, String commitId);

    /** 全表 HEAD 版本查询（跨校验/无 projectId 入口查询用，避免 findAll 载入历史版本行）。 */
    List<ClassInfo> findByIsHeadTrue();

    /** 全表指定 commitId 版本查询。 */
    List<ClassInfo> findByCommitId(String commitId);

    long countByProjectIdAndBranchAndPackageId(Long projectId, String branch, Long packageId);

    /**
     * 版本化写入前置：将该 (project_id, branch) 下所有 HEAD 行标记为非 HEAD。
     * 对应业务规则 BR-DIFF-001。
     */
    @Modifying
    @Query("UPDATE ClassInfo c SET c.isHead = false " +
           "WHERE c.projectId = :projectId AND c.branch = :branch AND c.isHead = true")
    int markHeadAsFalse(@Param("projectId") Long projectId, @Param("branch") String branch);

    /**
     * 版本化写入前置（按 sourceFilePath 限定）：增量更新时仅翻转让出文件对应行的 HEAD 标记。
     */
    @Modifying
    @Query("UPDATE ClassInfo c SET c.isHead = false " +
           "WHERE c.projectId = :projectId AND c.branch = :branch AND c.isHead = true " +
           "AND c.sourceFilePath = :sourceFilePath")
    int markHeadAsFalseForFile(@Param("projectId") Long projectId,
                               @Param("branch") String branch,
                               @Param("sourceFilePath") String sourceFilePath);
}
