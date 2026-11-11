package io.yuan.octopus.scenario.generation;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProbeEnvironmentHistoryRepository extends JpaRepository<ProbeEnvironmentHistory, String> {
    List<ProbeEnvironmentHistory> findTop50ByProbeEnvironmentIdOrderByRevisionDesc(String probeEnvironmentId);
}
