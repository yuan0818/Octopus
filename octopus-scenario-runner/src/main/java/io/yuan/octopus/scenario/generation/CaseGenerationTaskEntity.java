package io.yuan.octopus.scenario.generation;

import jakarta.persistence.*;
import java.time.Instant;

/** 生成任务的加密快照（ADR-CG-01）。active_flow 唯一，保证一个流程同时只有一个活动任务。 */
@Entity
@Table(name = "business_case_generation_task")
public class CaseGenerationTaskEntity {
    @Id @Column(length = 36) public String id;
    @Version public long version;
    @Column(name = "request_id", nullable = false, unique = true, length = 64) public String requestId;
    @Column(name = "request_hash", nullable = false, length = 64) public String requestHash;
    @Column(name = "flow_id", nullable = false, length = 160) public String flowId;
    @Column(name = "flow_revision", nullable = false) public int flowRevision;
    @Column(name = "active_flow", unique = true, length = 160) public String activeFlow;
    @Column(nullable = false, length = 24) public String state;
    @Column(length = 24) public String phase;
    @Column(name = "round_no", nullable = false) public int roundNo;
    @Column(name = "probe_mode", nullable = false, length = 8) public String probeMode;
    @Column(name = "probe_environment", length = 64) public String probeEnvironment;
    @Column(name = "snapshot_encrypted", nullable = false, columnDefinition = "LONGTEXT") public String snapshotEncrypted;
    @Column(length = 96) public String owner;
    @Column(name = "lease_until", nullable = false) public long leaseUntil;
    @Column(name = "created_by", length = 128) public String createdBy;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
