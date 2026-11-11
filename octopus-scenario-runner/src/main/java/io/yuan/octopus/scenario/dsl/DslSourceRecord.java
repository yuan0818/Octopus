package io.yuan.octopus.scenario.dsl;

import jakarta.persistence.*;
import java.time.Instant;

/** FR-CONV-03: the DSL source a flow draft revision was translated from, with both hashes. */
@Entity
@Table(name = "business_dsl_source")
public class DslSourceRecord {
    /** {@code flowId@revision}. */
    @Id public String id;

    @Column(nullable = false, length = 160)
    public String flowId;

    @Column(nullable = false)
    public int revision;

    @Column(nullable = false, length = 96)
    public String scenarioId;

    @Column(nullable = false, length = 64)
    public String sourceHash;

    @Column(nullable = false, length = 64)
    public String flowHash;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String source;

    @Column(nullable = false)
    public Instant createdAt;
}
