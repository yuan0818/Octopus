package io.yuan.octopus.codebase.repository;

import io.yuan.octopus.codebase.entity.RepoConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepoConfigRepository extends JpaRepository<RepoConfig, Long> {
    List<RepoConfig> findByEnabledTrue();
}
