package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;
import java.time.Instant;

/** A reviewed planning memory, separate from execution data and from the saved business flow. */
@Entity @Table(name = "business_step_memory")
public class StepMemory {
    @Id @Column(length = 36) public String id;
    @Column(name = "flow_id", nullable = false, length = 120) public String flowId;
    @Column(name = "node_id", nullable = false, length = 120) public String nodeId;
    @Column(nullable = false, length = 64) public String environment;
    @Column(nullable = false, length = 20) public String state;
    @Column(nullable = false, length = 64) public String fingerprint;
    @Column(name = "confirmation_hash", nullable = false, length = 64) public String confirmationHash;
    @Column(nullable = false, length = 200) public String model;
    @Column(name = "memory_json", nullable = false, columnDefinition = "LONGTEXT") public String memoryJson;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
