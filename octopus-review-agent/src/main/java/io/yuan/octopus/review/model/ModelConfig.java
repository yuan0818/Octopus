package io.yuan.octopus.review.model;

/**
 * Configuration for a single model.
 */
public class ModelConfig {

    private String name;            // 模型唯一标识，如 "claude-sonnet-4"
    private String provider;        // "claude" / "openai" / ...
    private String apiFormat;       // 报文格式：openai（兼容）/ claude；空则按 provider 推断
    private String modelId;         // 实际 API model 参数，如 "claude-sonnet-4-20250514"
    private String apiUrl;          // API 端点 URL
    private String apiKey;          // API Key
    private Integer maxTokens;      // 默认输出上限
    private Double temperature;     // 默认温度
    private boolean enabled;        // 是否启用
    private int weight = 100;       // 路由权重（同 provider 多模型时按权重分配）

    public ModelConfig() {}

    public ModelConfig(String name, String provider, String modelId,
                       String apiUrl, Integer maxTokens, Double temperature) {
        this.name = name;
        this.provider = provider;
        this.modelId = modelId;
        this.apiUrl = apiUrl;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.enabled = true;
    }

    // ── Getters & Setters ──

    public String getApiFormat() { return apiFormat; }
    public void setApiFormat(String apiFormat) { this.apiFormat = apiFormat; }

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
}
