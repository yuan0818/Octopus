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
            throw new ModelCallException("MODEL_CONFIG",null);
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new ModelCallException("MODEL_CONFIG",null);
        }
        if (model == null || model.isBlank()) {
            throw new ModelCallException("MODEL_CONFIG",null);
        }

        int maxTokens = config.getMaxTokens() != null ? config.getMaxTokens() : 128000;
        double temperature = config.getTemperature() != null ? config.getTemperature() : 0.3;

        boolean claudeFormat = isClaudeFormat(config, provider);
        String requestBody = buildRequestBody(systemPrompt, userPrompt, model, maxTokens, temperature, claudeFormat, config.getResponseFormat());
        log.info("Calling LLM: provider={}, model={}, maxTokens={}", provider, model, maxTokens);

        try {
            boolean isClaude = "claude".equals(provider);
            String apiEndpoint = isClaude
                    ? apiUrl + "/messages"
                    : apiUrl + "/chat/completions";

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(apiEndpoint))
                    .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds() == null ? 120 : Math.max(5, Math.min(600, config.getRequestTimeoutSeconds()))))
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
                log.error("LLM API error: provider={}, status={}", provider, response.statusCode());
                throw ModelCallException.http(response.statusCode());
            }

            log.info("LLM call completed: provider={}, model={}, elapsed={}ms", provider, model, elapsed);
            return parseResponse(response.body(), model, elapsed, claudeFormat);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            var failure=ModelCallException.classify(e);
            log.error("LLM call failed: {}", failure.code);
            throw failure;
        }
    }

    private String buildRequestBody(String systemPrompt, String userPrompt,
                                     String model, int maxTokens, double temperature, boolean claudeFormat) {
        return buildRequestBody(systemPrompt, userPrompt, model, maxTokens, temperature, claudeFormat, null);
    }

    /** format 为 null 时字段与未启用结构化输出时一致；否则按报文格式写入 response_format 或 output_config（SO-001）。 */
    static String buildRequestBody(String systemPrompt, String userPrompt, String model, int maxTokens, double temperature,
                                   boolean claudeFormat, io.yuan.octopus.review.dto.ResponseFormat format) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        if (claudeFormat) {
            body.put("system", systemPrompt);
            body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));
            // Anthropic 没有 JSON 模式；json_object 已在保存配置时拒绝，这里不发送任何格式字段
            if (format != null && io.yuan.octopus.review.dto.ResponseFormat.JSON_SCHEMA.equals(format.type()))
                body.put("output_config", Map.of("format", Map.of("type", "json_schema", "schema", format.schema())));
        } else {
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));
            if (format != null && io.yuan.octopus.review.dto.ResponseFormat.JSON_OBJECT.equals(format.type()))
                body.put("response_format", Map.of("type", "json_object"));
            else if (format != null && io.yuan.octopus.review.dto.ResponseFormat.JSON_SCHEMA.equals(format.type()))
                body.put("response_format", Map.of("type", "json_schema",
                        "json_schema", Map.of("name", format.name(), "strict", true, "schema", format.schema())));
        }
        return new Gson().toJson(body);
    }

    private LLMResponse parseResponse(String json, String model, long elapsedMs, boolean claudeFormat) {
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);

            String content;
            int promptTokens;
            int completionTokens;

            if (claudeFormat) {
                // 取第一个文本块（前面可能有 thinking 等其他类型的块）
                content = null;
                for (var block : root.getAsJsonArray("content")) {
                    var b = block.getAsJsonObject();
                    if (b.has("type") && "text".equals(b.get("type").getAsString())) { content = b.get("text").getAsString(); break; }
                }
                if (content == null) throw new IllegalStateException("no text block");
                JsonObject usage = root.getAsJsonObject("usage");
                promptTokens = usage.get("input_tokens").getAsInt();
                completionTokens = usage.get("output_tokens").getAsInt();
            } else {
                var message = root.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message");
                var text = message.get("content");
                // 严格模式下的拒答：content 为 null、refusal 为说明文字；作为内容返回，交给调用方的有界格式修正（SO-007）
                if ((text == null || text.isJsonNull()) && message.has("refusal") && !message.get("refusal").isJsonNull())
                    text = message.get("refusal");
                content = text.getAsString();
                JsonObject usage = root.getAsJsonObject("usage");
                promptTokens = usage.get("prompt_tokens").getAsInt();
                completionTokens = usage.get("completion_tokens").getAsInt();
            }

            return new LLMResponse(content, model, promptTokens, completionTokens);

        } catch (Exception e) {
            log.error("Failed to parse LLM response: MODEL_RESPONSE_INVALID");
            throw new ModelCallException("MODEL_RESPONSE_INVALID",null);
        }
    }

    /** 报文格式判定：显式 apiFormat 优先，否则按 provider 推断（claude→claude，其余→openai）。 */
    private static boolean isClaudeFormat(io.yuan.octopus.review.dto.LLMConfig config, String provider) {
        if (config != null && config.getApiFormat() != null && !config.getApiFormat().isBlank()) {
            return "claude".equalsIgnoreCase(config.getApiFormat());
        }
        return "claude".equals(provider);
    }
}
