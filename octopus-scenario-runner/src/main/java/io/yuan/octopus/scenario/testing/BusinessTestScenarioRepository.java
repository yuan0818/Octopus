package io.yuan.octopus.scenario.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessTestScenarioRepository
        extends JpaRepository<BusinessTestScenario, String> {
    List<BusinessTestScenario> findTop100ByOrderByCreatedAtDesc();

    /** Phase 2.5：同一测试点生成过的所有场景（每次批量回归都会生成新场景）。 */
    List<BusinessTestScenario> findTop500ByFlowIdAndTestPointIdOrderByCreatedAtDesc(String flowId, String testPointId);
}
