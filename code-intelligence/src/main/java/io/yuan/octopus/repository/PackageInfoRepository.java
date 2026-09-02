package io.yuan.octopus.repository;

import io.yuan.octopus.entity.PackageInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PackageInfoRepository extends JpaRepository<PackageInfo, Long> {

    void deleteByProjectId(Long projectId);

    void deleteByProjectIdAndBranch(Long projectId, String branch);

    /**
     * 版本化写入时按 (project_id, branch, package_name) 复用既有包行：
     * 旧版本 class 行的 package_id 引用因此保持有效，包表不再随分析无限膨胀。
     */
    Optional<PackageInfo> findByProjectIdAndBranchAndPackageName(Long projectId, String branch, String packageName);
}
