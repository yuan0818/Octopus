package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "business_test_scenario")
public class BusinessTestScenario {
    @Id public String id;

    @Column(nullable = false)
    public String flowId;

    @Column(nullable = false)
    public int flowRevision;

    @Column(nullable = false)
    public String testPointId;

    @Column(nullable = false, length = 64)
    public String irHash;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    public String irJson;

    @Column(nullable = false)
    public Instant createdAt;
}
