package io.yuan.octopus.review.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_model")
public class AgentModel {

    @Id
    @Column(name = "name", length = 100)
    private String name;            // 模型唯一标识

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;        // "claude" / "openai"

    @Column(name = "model_id", nullable = false, length = 100)
    private String modelId;         // API model 参数

    @Column(name = "api_url", nullable = false, length = 500)
    private String apiUrl;

    @Column(name = "api_key", length = 500)
    private String apiKey;          // API Key

    @Column(name = "max_tokens")
    private Integer maxTokens = 128000;

    @Column(name = "temperature", columnDefinition = "DECIMAL(3,2)")
    private Double temperature = 0.3;

    @Column(name = "enabled")
    private boolean enabled = true;

    @Column(name = "weight")
    private int weight = 100;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
