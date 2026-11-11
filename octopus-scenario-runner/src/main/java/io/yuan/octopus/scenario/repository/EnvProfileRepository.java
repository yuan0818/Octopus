package io.yuan.octopus.scenario.repository;

import io.yuan.octopus.scenario.entity.EnvProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvProfileRepository extends JpaRepository<EnvProfile, Long> {
}
