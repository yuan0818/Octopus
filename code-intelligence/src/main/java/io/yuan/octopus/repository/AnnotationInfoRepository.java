package io.yuan.octopus.repository;

import io.yuan.octopus.entity.AnnotationInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnnotationInfoRepository extends JpaRepository<AnnotationInfo, Long> {

    void deleteByTargetTypeAndTargetIdIn(String targetType, List<Long> targetIds);

    /** 版本化安全查询：仅取 HEAD 版本注解（旧版本注解行随分析累积）。 */
    List<AnnotationInfo> findByAnnotationNameAndIsHeadTrue(String annotationName);

    List<AnnotationInfo> findByAnnotationNameIn(List<String> annotationNames);

    List<AnnotationInfo> findByTargetTypeAndTargetId(String targetType, Long targetId);

    List<AnnotationInfo> findByAnnotationNameInAndTargetTypeAndTargetIdIn(
            List<String> annotationNames, String targetType, List<Long> targetIds);

    /**
     * 版本化写入前置：将该 (project_id, branch) 下所有 HEAD 注解行标记为非 HEAD。
     * annotation_info 含冗余 project_id/branch 列。对应业务规则 BR-DIFF-001。
     */
    @Modifying
    @Query("UPDATE AnnotationInfo a SET a.isHead = false " +
           "WHERE a.projectId = :projectId AND a.branch = :branch AND a.isHead = true")
    int markHeadAsFalse(@Param("projectId") Long projectId, @Param("branch") String branch);

    /**
     * 增量写入前置（按 target 限定）：仅翻转变更文件对应 class/method/field 的注解 HEAD，
     * 未变更文件的注解保持 is_head=true（替代整分支全量翻转）。
     */
    @Modifying
    @Query("UPDATE AnnotationInfo a SET a.isHead = false " +
           "WHERE a.projectId = :projectId AND a.branch = :branch AND a.isHead = true " +
           "AND a.targetType = 'CLASS' AND a.targetId IN :targetIds")
    int markHeadAsFalseForClassTargets(@Param("projectId") Long projectId,
                                       @Param("branch") String branch,
                                       @Param("targetIds") List<Long> targetIds);

    @Modifying
    @Query("UPDATE AnnotationInfo a SET a.isHead = false " +
           "WHERE a.projectId = :projectId AND a.branch = :branch AND a.isHead = true " +
           "AND a.targetType = 'METHOD' AND a.targetId IN :targetIds")
    int markHeadAsFalseForMethodTargets(@Param("projectId") Long projectId,
                                        @Param("branch") String branch,
                                        @Param("targetIds") List<Long> targetIds);

    @Modifying
    @Query("UPDATE AnnotationInfo a SET a.isHead = false " +
           "WHERE a.projectId = :projectId AND a.branch = :branch AND a.isHead = true " +
           "AND a.targetType = 'FIELD' AND a.targetId IN :targetIds")
    int markHeadAsFalseForFieldTargets(@Param("projectId") Long projectId,
                                       @Param("branch") String branch,
                                       @Param("targetIds") List<Long> targetIds);
}
