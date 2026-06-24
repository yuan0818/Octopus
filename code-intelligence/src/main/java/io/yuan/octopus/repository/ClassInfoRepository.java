package io.yuan.octopus.repository;

import io.yuan.octopus.entity.ClassInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassInfoRepository extends JpaRepository<ClassInfo, Long> {

    void deleteByProjectId(Long projectId);

    Optional<ClassInfo> findByQualifiedName(String qualifiedName);

    List<ClassInfo> findByProjectIdAndQualifiedNameContaining(Long projectId, String packageName);

    List<ClassInfo> findByQualifiedNameStartingWith(String prefix);

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

    List<ClassInfo> findByProjectIdAndBranch(Long projectId, String branch);

    long countByProjectIdAndBranchAndPackageId(Long projectId, String branch, Long packageId);
}
