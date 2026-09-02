package io.yuan.octopus.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.LocalDateTime;

/**
 * 需求源连接配置（requirement_source_config，每种来源一行）。
 * 通用字段 + extraJson（JSON）覆盖来源差异：
 * <ul>
 *   <li>jira:  baseUrl + account(email) + secret(api token)</li>
 *   <li>tapd:  baseUrl(默认 api.tapd.cn) + account(api_user) + secret(api_password) + extra.workspaceId</li>
 *   <li>feishu: baseUrl(默认 open.feishu.cn) + account(app_id) + secret(app_secret)</li>
 * </ul>
 */
@Entity
@Table(name = "requirement_source_config")
public class RequirementSourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源标识，与适配器 sourceType 对应。 */
    @Column(name = "source_type", length = 30, nullable = false, unique = true)
    private String sourceType;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /** 账号（email / api_user / app_id，按来源解释）。 */
    @Column(length = 200)
    private String account;

    /** 密钥（api token / api_password / app_secret）。返回给前端时脱敏。 */
    @Column(length = 500)
    private String secret;

    /** 扩展字段 JSON，如 {"workspaceId":"123"}。 */
    @Column(name = "extra_json", columnDefinition = "TEXT")
    private String extraJson;

    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Getters & Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public String getExtraJson() { return extraJson; }
    public void setExtraJson(String extraJson) { this.extraJson = extraJson; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
