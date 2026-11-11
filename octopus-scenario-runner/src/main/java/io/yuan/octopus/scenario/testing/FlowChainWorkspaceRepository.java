package io.yuan.octopus.scenario.testing;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface FlowChainWorkspaceRepository extends JpaRepository<FlowChainWorkspace,String> {
    Optional<FlowChainWorkspace> findByRequestId(String requestId);
    Optional<FlowChainWorkspace> findByActiveFlow(String flowId);
    Optional<FlowChainWorkspace> findFirstByFlowIdOrderByCreatedAtDesc(String flowId);
    List<FlowChainWorkspace> findTop50ByStateNotOrderByUpdatedAtDesc(String state);
    List<FlowChainWorkspace> findTop100ByOrderByUpdatedAtDescIdDesc();
    @Query("select w.id from FlowChainWorkspace w where w.state in ('QUEUED','RUNNING','PAUSE_REQUESTED','ACCEPTING')")
    List<String> pending();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from FlowChainWorkspace w where w.id = :id")
    Optional<FlowChainWorkspace> locked(String id);
}
