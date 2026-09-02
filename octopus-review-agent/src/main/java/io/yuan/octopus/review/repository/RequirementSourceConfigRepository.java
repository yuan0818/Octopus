package io.yuan.octopus.review.repository;

import io.yuan.octopus.review.entity.RequirementSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RequirementSourceConfigRepository extends JpaRepository<RequirementSourceConfig, Long> {

    Optional<RequirementSourceConfig> findBySourceType(String sourceType);
}
