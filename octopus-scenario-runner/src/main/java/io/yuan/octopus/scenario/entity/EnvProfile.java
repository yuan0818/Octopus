package io.yuan.octopus.scenario.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 环境档案：baseUrl + 网关头 + 密钥（密钥加密存储）。人配一次，场景引用。 */
@Entity
@Table(name = "scenario_env_profile")
public class EnvProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /** JSON：网关头/认证策略（静态值或 ${env.x} 引用）。 */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /** 加密后的密钥 JSON（${env.k} 的 k 值域），AES-GCM。 */
    @Column(name = "secrets_encrypted", columnDefinition = "TEXT")
    private String secretsEncrypted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getSecretsEncrypted() { return secretsEncrypted; }
    public void setSecretsEncrypted(String secretsEncrypted) { this.secretsEncrypted = secretsEncrypted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
