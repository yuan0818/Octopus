package io.yuan.octopus.repository;

import io.yuan.octopus.entity.RepoConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoConfigRepository extends JpaRepository<RepoConfig, Long> {

    /** 按 repoUrl 匹配仓库配置（webhook payload 按 repoUrl 匹配 RepoConfig，BR-CTR-004）。 */
    Optional<RepoConfig> findByRepoUrl(String repoUrl);

    /** 按平台筛选仓库配置（仅 GERRIT 的仓库触发变更审查）。 */
    List<RepoConfig> findByPlatform(String platform);

    /** 按平台 + repoUrl 子串匹配（Gerrit webhook payload 仅含 project 名，需映射到 RepoConfig.repoUrl）。 */
    List<RepoConfig> findByPlatformAndRepoUrlContaining(String platform, String substring);
}
