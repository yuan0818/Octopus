package io.yuan.octopus.scenario.repository;

import io.yuan.octopus.scenario.entity.ScenarioApiRef;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ScenarioApiRefRepository extends JpaRepository<ScenarioApiRef, Long> {
    List<ScenarioApiRef> findByProjectIdAndApiIdentityIn(Long projectId, List<String> apiIdentities);
    void deleteByScenarioId(Long scenarioId);
    List<ScenarioApiRef> findByScenarioId(Long scenarioId);
}
