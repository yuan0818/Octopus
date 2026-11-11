package io.yuan.octopus.scenario.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataRunRepository extends JpaRepository<DataRun, String> {
    List<DataRun> findByStatus(String status);

    List<DataRun> findTop100ByScenarioIdOrderByCreatedAtDesc(String scenarioId);
}
