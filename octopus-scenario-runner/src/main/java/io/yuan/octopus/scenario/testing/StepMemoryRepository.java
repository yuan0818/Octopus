package io.yuan.octopus.scenario.testing;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface StepMemoryRepository extends JpaRepository<StepMemory, String> {
    List<StepMemory> findTop500ByFlowIdAndEnvironmentAndStateOrderByCreatedAtDesc(String flowId, String environment, String state);
}
