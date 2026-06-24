package io.yuan.octopus.entity;

import io.yuan.octopus.common.AuthType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "repo_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepoConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_url", nullable = false, length = 500)
    private String repoUrl;

    @Column(nullable = false, length = 100)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20)
    private AuthType authType;

    @Column(name = "http_username", length = 200)
    private String httpUsername;

    @Column(name = "http_credential_encrypted", length = 500)
    private String httpCredentialEncrypted;

    @Column(name = "ssh_private_key_path", length = 500)
    private String sshPrivateKeyPath;

    @Column(name = "ssh_passphrase_encrypted", length = 500)
    private String sshPassphraseEncrypted;

    @Column(name = "ssh_known_hosts_path", length = 500)
    private String sshKnownHostsPath;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
