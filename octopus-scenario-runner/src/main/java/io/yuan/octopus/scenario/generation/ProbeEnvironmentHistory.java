package io.yuan.octopus.scenario.generation;

import jakarta.persistence.*;
import java.time.Instant;

/** 探测授权的每次变更（NFR-010 审计）。只追加，不修改。 */
@Entity
@Table(name = "business_probe_environment_history")
public class ProbeEnvironmentHistory {
    @Id @Column(length = 36) public String id;
    @Column(name = "probe_environment_id", nullable = false, length = 36) public String probeEnvironmentId;
    @Column(nullable = false) public int revision;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "LONGTEXT") public String snapshotJson;
    @Column(name = "changed_by", length = 128) public String changedBy;
    @Column(name = "changed_at", nullable = false) public Instant changedAt;
}
