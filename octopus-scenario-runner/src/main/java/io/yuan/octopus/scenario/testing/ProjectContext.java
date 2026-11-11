package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;
import java.time.Instant;

/** Credentials live only inside encrypted payloads, separate from descriptive knowledge. */
@Entity
@Table(name = "business_project_context")
public class ProjectContext {
    @Id @Column(length = 64) public String id;
    @Version public Long version;
    @Column(name = "repository_key", nullable = false, length = 1000) public String repositoryKey;
    @Column(name = "module_path", nullable = false, length = 1000) public String modulePath;
    @Column(nullable = false, length = 64) public String environment;
    @Column(name = "definition_json", nullable = false, columnDefinition = "LONGTEXT") public String definitionJson;
    @Column(name = "credentials_encrypted", nullable = false, columnDefinition = "LONGTEXT") public String credentialsEncrypted;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
