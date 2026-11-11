package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * FR-POLL-01: a user's explicit authorization that one specific API may be polled, with fixed bounds.
 * The platform never creates, suggests or widens these (FR-POLL-02). Identity is the API's stable
 * identity: project + HTTP method + path template + method signature, so it carries across commits.
 */
@Entity
@Table(name = "business_poll_authorization")
public class PollAuthorization {
    @Id public String id;
    @Version public long version;

    @Column(nullable = false)
    public long projectId;

    @Column(nullable = false, length = 16)
    public String httpMethod;

    @Column(nullable = false, length = 512)
    public String path;

    /** Method signature with whitespace removed. */
    @Column(nullable = false, length = 1024)
    public String signature;

    /** Increments on every change; flows pin {@code id@revision}. */
    @Column(nullable = false)
    public int revision;

    @Column(nullable = false)
    public long intervalMillis;

    @Column(nullable = false)
    public int maxAttempts;

    @Column(nullable = false)
    public long timeoutMillis;

    @Column(nullable = false, length = 500)
    public String reason;

    @Column(nullable = false)
    public boolean revoked;

    @Column(length = 128)
    public String createdBy;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(length = 128)
    public String updatedBy;

    @Column(nullable = false)
    public Instant updatedAt;

    public String ref() {
        return id + "@" + revision;
    }
}
