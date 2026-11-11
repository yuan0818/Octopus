package io.yuan.octopus.scenario.generation;

import jakarta.persistence.*;
import java.time.Instant;

/** ENVG-001：环境级“允许生成探测”授权。默认不存在即关闭；生产环境不能开启。 */
@Entity
@Table(name = "business_probe_environment")
public class ProbeEnvironment {
    @Id @Column(length = 36) public String id;
    @Version public long version;
    @Column(name = "workspace_id", nullable = false, length = 64) public String workspaceId;
    @Column(nullable = false, length = 64) public String environment;
    @Column(nullable = false) public boolean enabled;
    /** CLEANUP_REQUIRED | DISPOSABLE（ENVG-002）。 */
    @Column(name = "data_policy", nullable = false, length = 24) public String dataPolicy;
    @Column(name = "reset_cycle_hours") public Integer resetCycleHours;
    @Column(name = "non_production_declared", nullable = false) public boolean nonProductionDeclared;
    @Column(nullable = false, length = 500) public String reason;
    @Column(nullable = false) public int revision;
    @Column(name = "updated_by", length = 128) public String updatedBy;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
