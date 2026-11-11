package io.yuan.octopus.scenario.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 变更联动倒排索引：API → 引用它的场景（保存场景时从 DAG 派生维护）。 */
@Entity
@Table(name = "scenario_api_ref",
        indexes = @Index(name = "idx_sar_api", columnList = "project_id,api_identity"))
public class ScenarioApiRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    /** API 标识（method:normalizedPath，如 GET:/api/orders/{id}）。 */
    @Column(name = "api_identity", nullable = false, length = 300)
    private String apiIdentity;

    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getApiIdentity() { return apiIdentity; }
    public void setApiIdentity(String apiIdentity) { this.apiIdentity = apiIdentity; }
    public Long getScenarioId() { return scenarioId; }
    public void setScenarioId(Long scenarioId) { this.scenarioId = scenarioId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
