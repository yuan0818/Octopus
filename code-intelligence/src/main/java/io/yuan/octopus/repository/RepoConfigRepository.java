package io.yuan.octopus.repository;

import io.yuan.octopus.entity.RepoConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoConfigRepository extends JpaRepository<RepoConfig, Long> {
}
