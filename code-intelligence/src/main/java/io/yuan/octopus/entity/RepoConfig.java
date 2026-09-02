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

    // ── 平台集成字段（只读；由 octopus-code-base CRUD 写入，code-intelligence 读取供 Gerrit 操作用，ADR-003） ──

    @Column(name = "platform", nullable = false, length = 20)
    private String platform = "NONE";   // NONE / GERRIT / GITLAB(预留)

    @Column(name = "platform_url", length = 500)
    private String platformUrl;        // 如 https://gerrit.example.com

    @Column(name = "platform_token_encrypted", length = 500)
    private String platformTokenEncrypted;  // Gerrit REST 凭证（加密）

    @Column(name = "webhook_secret_encrypted", length = 500)
    private String webhookSecretEncrypted;  // webhook 校验 secret（加密）

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
