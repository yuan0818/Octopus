package io.yuan.octopus.repository;

import io.yuan.octopus.entity.PackageInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageInfoRepository extends JpaRepository<PackageInfo, Long> {

    void deleteByProjectId(Long projectId);

    void deleteByProjectIdAndBranch(Long projectId, String branch);
}
