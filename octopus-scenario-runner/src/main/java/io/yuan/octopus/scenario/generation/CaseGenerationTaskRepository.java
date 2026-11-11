package io.yuan.octopus.scenario.generation;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface CaseGenerationTaskRepository extends JpaRepository<CaseGenerationTaskEntity, String> {
    Optional<CaseGenerationTaskEntity> findByRequestId(String requestId);
    Optional<CaseGenerationTaskEntity> findByActiveFlow(String flowId);
    List<CaseGenerationTaskEntity> findTop50ByFlowIdOrderByCreatedAtDesc(String flowId);
    List<CaseGenerationTaskEntity> findTop100ByOrderByUpdatedAtDesc();
    @Query("select t.id from CaseGenerationTaskEntity t where t.state in ('QUEUED','RUNNING','CANCEL_REQUESTED')")
    List<String> pending();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CaseGenerationTaskEntity t where t.id = :id")
    Optional<CaseGenerationTaskEntity> locked(String id);
}
