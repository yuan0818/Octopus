package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;
import java.time.Instant;

/** A durable submission receipt contains digests and identifiers, never request credentials. */
@Entity
@Table(name="business_run_submission")
public class RunSubmission {
    @Id @Column(length=64) public String id;
    @Version public Long version;
    @Column(nullable=false,length=64) public String inputHash;
    @Column(nullable=false,length=36) public String runId;
    @Column(nullable=false) public Instant createdAt;
}
