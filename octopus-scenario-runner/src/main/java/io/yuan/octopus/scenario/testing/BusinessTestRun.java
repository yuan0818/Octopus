package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "business_test_run")
public class BusinessTestRun {
    @Id public String id;
    @Version public long version;

    @Column(nullable = false)
    public String scenarioId;

    @Column(nullable = false)
    public String state;

    @Column(nullable = false)
    public String verdict;

    @Column(columnDefinition = "LONGTEXT")
    public String reportJson;

    public String error;

    @Column(nullable = false)
    public Instant createdAt;

    public Instant finishedAt;

    /** Phase 2.2：执行此运行的 worker（进程实例），以及最近一次心跳。用于识别进程退出后遗留的 QUEUED / RUNNING。 */
    @Column(length = 96)
    public String workerId;

    public Instant heartbeatAt;
}
