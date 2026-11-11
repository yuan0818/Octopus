package io.yuan.octopus.scenario.generation;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProbeEnvironmentRepository extends JpaRepository<ProbeEnvironment, String> {
    Optional<ProbeEnvironment> findByWorkspaceIdAndEnvironment(String workspaceId, String environment);
    List<ProbeEnvironment> findByWorkspaceIdOrderByEnvironment(String workspaceId);
}
