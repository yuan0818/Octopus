package io.yuan.octopus.repository;

import io.yuan.octopus.entity.MethodInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MethodInfoRepository extends JpaRepository<MethodInfo, Long> {

    void deleteByClassInfoId(Long classId);

    void deleteByClassInfoIdIn(List<Long> classIds);

    List<MethodInfo> findByClassInfoId(Long classId);

    List<MethodInfo> findByClassInfoIdIn(List<Long> classIds);

    /**
     * 按 classId + methodSignature 查找方法（用于 PSI 行号更新时定位已写入的方法行）。
     */
    Optional<MethodInfo> findByClassInfoIdAndMethodSignature(Long classId, String methodSignature);

    /**
     * 增量写入前收集该 class 行当前 HEAD 方法（注解按 target 翻转用）。
     */
    List<MethodInfo> findByClassInfoIdAndIsHeadTrue(Long classId);

    /**
     * 版本化写入前置：将该 (project_id, branch) 下所有 HEAD 方法行标记为非 HEAD。
     * method_info 无独立 branch 列，通过 class_info JOIN 获取 project_id/branch。
     * 对应业务规则 BR-DIFF-001。
     */
    @Modifying
    @Query(value = "UPDATE method_info mi " +
           "JOIN class_info ci ON mi.class_id = ci.id " +
           "SET mi.is_head = 0 " +
           "WHERE ci.project_id = :projectId AND ci.branch = :branch AND mi.is_head = 1",
           nativeQuery = true)
    int markHeadAsFalse(@Param("projectId") Long projectId, @Param("branch") String branch);

    /**
     * 增量更新（按 sourceFilePath 限定）：仅翻转该文件对应方法行的 HEAD 标记。
     */
    @Modifying
    @Query(value = "UPDATE method_info mi " +
           "JOIN class_info ci ON mi.class_id = ci.id " +
           "SET mi.is_head = 0 " +
           "WHERE ci.project_id = :projectId AND ci.branch = :branch " +
           "AND ci.source_file_path = :sourceFilePath AND mi.is_head = 1",
           nativeQuery = true)
    int markHeadAsFalseForFile(@Param("projectId") Long projectId,
                               @Param("branch") String branch,
                               @Param("sourceFilePath") String sourceFilePath);
}
