package io.yuan.octopus.scenario.repository;

import io.yuan.octopus.scenario.entity.Scenario;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {
    List<Scenario> findByProjectIdOrderByUpdatedAtDesc(Long projectId);
}
