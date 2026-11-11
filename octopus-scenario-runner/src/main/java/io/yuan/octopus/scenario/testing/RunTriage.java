package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;

import java.time.Instant;

/** Phase 1D：一次失败运行的 LLM 建议性分析（不影响运行判定）。 */
@Entity
@Table(name = "business_run_triage")
public class RunTriage {
    @Id public String runId;
    @Version public long version;

    @Column(nullable = false, length = 32)
    public String category;

    @Column(nullable = false, length = 16)
    public String confidence;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String triageJson;

    @Column(length = 128)
    public String model;

    @Column(nullable = false)
    public Instant createdAt;
}
