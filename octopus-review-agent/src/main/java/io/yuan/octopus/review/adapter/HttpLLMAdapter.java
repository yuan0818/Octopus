package io.yuan.octopus.review.adapter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.yuan.octopus.review.dto.LLMConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class HttpLLMAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpLLMAdapter.class);

    private final HttpClient client;
    private final Gson gson;

    public HttpLLMAdapter() {
        this.gson = new Gson();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String provider() {
        return "universal";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public LLMResponse call(String systemPrompt, String userPrompt, LLMConfig config) {
        String provider = config.getProvider();
        String model = config.getModel();
        String apiKey = config.getApiKey();
        String apiUrl = config.getApiUrl();

        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("未配置 API Key，请在 Agent 配置 > 模型管理 中设置 provider '" + provider + "' 的 API Key");
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new RuntimeException("未配置 API URL，请在 Agent 配置 > 模型管理 中设置 provider '" + provider + "' 的 API URL");
        }
        if (model == null || model.isBlank()) {
            throw new RuntimeException("未配置模型 ID，请在 Agent 配置 > 模型管理 中设置");
        }

        int maxTokens = config.getMaxTokens() != null ? config.getMaxTokens() : 128000;
        double temperature = config.getTemperature() != null ? config.getTemperature() : 0.3;

        String requestBody = buildRequestBody(systemPrompt, userPrompt, model, maxTokens, temperature, provider);
        log.info("Calling LLM: provider={}, model={}, maxTokens={}, apiUrl={}", provider, model, maxTokens, apiUrl);

        try {
            boolean isClaude = "claude".equals(provider);
            String apiEndpoint = isClaude
                    ? apiUrl + "/messages"
                    : apiUrl + "/chat/completions";

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(apiEndpoint))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey);
            if (isClaude) {
                reqBuilder.header("anthropic-version", "2023-06-01");
            }
            HttpRequest request = reqBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                log.error("LLM API error: provider={}, status={}, body={}", provider, response.statusCode(), response.body());
                String snippet = response.body().substring(0, Math.min(200, response.body().length()));
                throw new RuntimeException("LLM API 返回 " + response.statusCode() + ": " + snippet);
            }

            log.info("LLM call completed: provider={}, model={}, elapsed={}ms", provider, model, elapsed);
            return parseResponse(response.body(), model, elapsed, provider);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(String systemPrompt, String userPrompt,
                                     String model, int maxTokens, double temperature, String provider) {
        if ("claude".equals(provider)) {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "temperature", temperature,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))
            );
            return gson.toJson(body);
        } else {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "temperature", temperature,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );
            return gson.toJson(body);
        }
    }

    private LLMResponse parseResponse(String json, String model, long elapsedMs, String provider) {
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);

            String content;
            int promptTokens;
            int completionTokens;

            if ("claude".equals(provider)) {
                content = root.getAsJsonArray("content")
                        .get(0).getAsJsonObject()
                        .get("text").getAsString();
                JsonObject usage = root.getAsJsonObject("usage");
                promptTokens = usage.get("input_tokens").getAsInt();
                completionTokens = usage.get("output_tokens").getAsInt();
            } else {
                content = root.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();
                JsonObject usage = root.getAsJsonObject("usage");
                promptTokens = usage.get("prompt_tokens").getAsInt();
                completionTokens = usage.get("completion_tokens").getAsInt();
            }

            return new LLMResponse(content, model, promptTokens, completionTokens);

        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return new LLMResponse(json, model, 0, 0);
        }
    }
}
