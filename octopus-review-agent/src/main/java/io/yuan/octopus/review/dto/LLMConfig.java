package io.yuan.octopus.review.dto;

public class LLMConfig {

    private String provider;  // null means "use skill's preferred provider"
    private String apiFormat; // 报文格式：openai（兼容）/ claude；空则按 provider 推断
    private String model;
    private Integer maxTokens = 128000;
    private Double temperature = 0.3;
    private String apiKey;
    private String apiUrl;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

    public String getApiFormat() { return apiFormat; }
    public void setApiFormat(String apiFormat) { this.apiFormat = apiFormat; }
}
