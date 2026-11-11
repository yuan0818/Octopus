package io.yuan.octopus.scenario.generation;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProbeRunRepository extends JpaRepository<ProbeRun, String> {
    Optional<ProbeRun> findByIdempotencyKey(String key);
    List<ProbeRun> findByTaskIdOrderByCreatedAt(String taskId);
    List<ProbeRun> findByStateIn(Collection<String> states);

    /** 心跳只更新时间戳，不递增版本号。 */
    @Modifying @Transactional
    @Query("update ProbeRun r set r.heartbeatAt = :now where r.id in :ids and r.state in ('QUEUED','RUNNING')")
    int heartbeat(@Param("ids") Collection<String> ids, @Param("now") Instant now);
}
