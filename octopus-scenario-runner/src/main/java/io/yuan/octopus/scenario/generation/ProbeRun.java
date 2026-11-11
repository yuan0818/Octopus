package io.yuan.octopus.scenario.generation;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 生成探测运行（ADR-CG-04）：与业务运行 business_test_run 物理隔离，因此不进入业务报告、覆盖率、回归统计或门禁（OBS-007）。
 * idempotencyKey = taskId:round:intentId，保证检查点恢复时绝不重复提交（LOOP-005）。
 */
@Entity
@Table(name = "business_generation_probe_run")
public class ProbeRun {
    @Id @Column(length = 36) public String id;
    @Version public long version;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160) public String idempotencyKey;
    @Column(name = "task_id", nullable = false, length = 36) public String taskId;
    @Column(name = "round_no", nullable = false) public int roundNo;
    @Column(name = "intent_id", nullable = false, length = 96) public String intentId;
    @Column(name = "candidate_hash", nullable = false, length = 64) public String candidateHash;
    @Column(name = "ir_hash", nullable = false, length = 64) public String irHash;
    @Column(nullable = false, length = 64) public String environment;
    @Column(name = "probe_env_revision", nullable = false) public int probeEnvRevision;
    @Column(nullable = false, length = 40) public String namespace;
    /** QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED | TIMED_OUT | INTERRUPTED | REJECTED */
    @Column(nullable = false, length = 24) public String state;
    /** RUNNING 之后才可能发出业务请求；QUEUED 中断或启动前拒绝为 false。 */
    @Column(name = "requests_may_have_been_sent", nullable = false) public boolean requestsMayHaveBeenSent;
    @Column(name = "report_json", columnDefinition = "LONGTEXT") public String reportJson;
    @Column(name = "evidence_json", columnDefinition = "LONGTEXT") public String evidenceJson;
    @Column(name = "cleanup_status", length = 24) public String cleanupStatus;
    @Column(length = 500) public String error;
    @Column(name = "worker_id", length = 96) public String workerId;
    @Column(name = "heartbeat_at") public Instant heartbeatAt;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "finished_at") public Instant finishedAt;

    public boolean terminal() { return !"QUEUED".equals(state) && !"RUNNING".equals(state); }
}
