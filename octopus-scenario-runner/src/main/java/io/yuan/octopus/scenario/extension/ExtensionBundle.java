package io.yuan.octopus.scenario.extension;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One uploaded extension JAR and the tools it declares. Published bundles are immutable: the same
 * {@code name@version} can never be registered again with different content (FR-EXT-26).
 */
@Entity
@Table(name = "business_tool_extension")
public class ExtensionBundle {
    public enum Status { REJECTED, VALIDATED, PUBLISHED, DISABLED }

    @Id public String id;
    @Version public long version;

    @Column(nullable = false, length = 255)
    public String fileName;

    @Column(nullable = false, unique = true, length = 64)
    public String sha256;

    @Column(nullable = false)
    public long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    /** Non-empty marks every tool in the bundle deprecated; the text names the replacement. */
    @Column(length = 500)
    public String deprecated;

    /** List of {@link ExtensionModel.Tool}. */
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String toolsJson;

    /** All issues from inspection, conflict checks and isolated verification. */
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String issuesJson;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String classNamesJson;

    @Column(length = 128)
    public String uploadedBy;

    @Column(nullable = false)
    public Instant uploadedAt;

    @Column(length = 128)
    public String publishedBy;

    public Instant publishedAt;

    @Column(nullable = false)
    public Instant updatedAt;
}
