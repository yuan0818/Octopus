package io.yuan.octopus.codebase.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "repo_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RepoConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_url", nullable = false, length = 500)
    private String repoUrl;

    @Column(nullable = false, length = 100)
    private String branch;

    @Column(name = "auth_type", nullable = false, length = 20)
    private String authType = "NONE";   // NONE / HTTP / SSH

    // ── 平台集成字段（变更触发审查流水线，ADR-003） ──

    @Column(name = "platform", nullable = false, length = 20)
    private String platform = "NONE";   // NONE / GERRIT / GITLAB(预留)

    @Column(name = "platform_url", length = 500)
    private String platformUrl;         // 如 https://gerrit.example.com

    @JsonIgnore
    @Column(name = "platform_token_encrypted", length = 500)
    private String platformTokenEncrypted;  // Gerrit REST 凭证 username:http_pass（加密）

    @JsonIgnore
    @Column(name = "webhook_secret_encrypted", length = 500)
    private String webhookSecretEncrypted;  // webhook 校验 secret（加密）

    // ── 请求态明文字段（@Transient，仅用于接收写入请求；控制器加密后落 encrypted 列，永不出现在响应中） ──

    @Transient
    private String platformToken;     // 明文 Gerrit REST 凭证 username:http_pass

    @Transient
    private String webhookSecret;    // 明文 webhook 校验 secret

    @Transient
    private String httpCredential;   // 明文 HTTP 密码 / token（写入请求专用）

    @Transient
    private String sshPassphrase;    // 明文 SSH 私钥口令（写入请求专用）

    @Column(name = "http_username", length = 200)
    private String httpUsername;

    @JsonIgnore
    @Column(name = "http_credential_encrypted", length = 500)
    private String httpCredentialEncrypted;

    @Column(name = "ssh_private_key_path", length = 500)
    private String sshPrivateKeyPath;

    @JsonIgnore
    @Column(name = "ssh_passphrase_encrypted", length = 500)
    private String sshPassphraseEncrypted;

    @Column(name = "ssh_known_hosts_path", length = 500)
    private String sshKnownHostsPath;

    @Column(name = "source_paths", length = 500)
    private String sourcePaths;

    @Column(name = "local_path", length = 500)
    private String localPath;

    @Column(name = "sync_status", length = 20)
    private String syncStatus = "pending";

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_commit_id", length = 100)
    private String lastCommitId;

    @Column(name = "enabled")
    private Boolean enabled = true;

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
