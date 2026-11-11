package io.yuan.octopus.scenario.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessTestRunRepository extends JpaRepository<BusinessTestRun, String> {
    List<BusinessTestRun> findTop100ByOrderByCreatedAtDesc();

    List<BusinessTestRun> findByStateIn(List<String> states);

    /** Phase 2.5：一组场景的最近运行。 */
    List<BusinessTestRun> findTop200ByScenarioIdInOrderByCreatedAtDesc(java.util.Collection<String> scenarioIds);

    /** 心跳：只更新时间戳，不递增版本号，避免与执行线程的保存冲突。 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update BusinessTestRun r set r.heartbeatAt = :now where r.id in :ids and r.state in ('QUEUED','RUNNING')")
    int heartbeat(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids,
                  @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
