package io.yuan.octopus.scenario.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 场景主档：DAG 文档（canonical JSON）+ 当前版本指针。 */
@Entity
@Table(name = "scenario")
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "env_profile_id")
    private Long envProfileId;

    /** DAG canonical JSON：steps[]（人组装，含工具粗标注与 calls[]）。 */
    @Column(name = "dag_json", columnDefinition = "MEDIUMTEXT")
    private String dagJson;

    /** 当前生效的 IR（LLM 组装产物）。 */
    @Column(name = "ir_json", columnDefinition = "MEDIUMTEXT")
    private String irJson;

    /** 当前版本号。 */
    @Column(name = "current_version")
    private int currentVersion = 1;

    /** IR 相对 DAG 是否过期（DAG 编辑后置 true，重组装后清除）。 */
    @Column(name = "ir_stale")
    private boolean irStale = true;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getEnvProfileId() { return envProfileId; }
    public void setEnvProfileId(Long envProfileId) { this.envProfileId = envProfileId; }
    public String getDagJson() { return dagJson; }
    public void setDagJson(String dagJson) { this.dagJson = dagJson; }
    public String getIrJson() { return irJson; }
    public void setIrJson(String irJson) { this.irJson = irJson; }
    public int getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(int currentVersion) { this.currentVersion = currentVersion; }
    public boolean isIrStale() { return irStale; }
    public void setIrStale(boolean irStale) { this.irStale = irStale; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
