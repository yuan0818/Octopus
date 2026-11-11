package io.yuan.octopus.review.model;

import io.yuan.octopus.review.adapter.HttpLLMAdapter;
import io.yuan.octopus.review.dto.LLMConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tests model connectivity by sending a minimal prompt.
 */
@Component
public class ModelTester {

    private static final Logger log = LoggerFactory.getLogger(ModelTester.class);

    private static final String TEST_PROMPT = "Respond with exactly: OK";

    private final ModelRegistry modelRegistry;

    public ModelTester(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /**
     * Test a model by sending a simple prompt.
     */
    public ModelTestResult test(ModelConfig config) {
        long start = System.currentTimeMillis();
        Map<String, Object> detail = new LinkedHashMap<>();

        try {
            String apiKey = config.getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = modelRegistry.getApiKey(config.getProvider());
            }
            if (apiKey == null || apiKey.isBlank()) {
                return new ModelTestResult(false, "API key not configured", 0, Map.of("error", "No API key"));
            }

            HttpLLMAdapter adapter = new HttpLLMAdapter();
            LLMConfig llmConfig = new LLMConfig();
            llmConfig.setProvider(config.getProvider());
            llmConfig.setApiFormat(config.getApiFormat());
            llmConfig.setModel(config.getModelId());
            llmConfig.setApiKey(apiKey);
            llmConfig.setApiUrl(config.getApiUrl());
            llmConfig.setMaxTokens(10);
            llmConfig.setTemperature(0.0);

            var response = adapter.call("You are a test harness.", TEST_PROMPT, llmConfig);
            long elapsed = System.currentTimeMillis() - start;

            detail.put("model", response.model());
            detail.put("promptTokens", response.promptTokens());
            detail.put("completionTokens", response.completionTokens());
            detail.put("responsePreview", response.content().substring(0, Math.min(100, response.content().length())));

            String failure = structuredCheck(adapter, config, llmConfig, detail);
            elapsed = System.currentTimeMillis() - start;
            if (failure != null) return new ModelTestResult(false, failure, (int) elapsed, detail);
            return new ModelTestResult(true, "OK (" + elapsed + "ms)", (int) elapsed, detail);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            detail.put("errorType", e.getClass().getSimpleName());
            detail.put("errorMessage", e.getMessage());
            return new ModelTestResult(false, e.getMessage(), (int) elapsed, detail);
        }
    }

    static final java.util.Map<String, Object> PROBE_SCHEMA = java.util.Map.of("type", "object",
            "properties", java.util.Map.of("ok", java.util.Map.of("type", "boolean")), "required", java.util.List.of("ok"), "additionalProperties", false);

    /**
     * SO-012：启用结构化输出时再发一次最小结构化请求，确认端点接受所选模式。返回失败说明，通过时返回 null。
     * 端点拒绝该模式时，用例生成调用会以 MODEL_* 失败而不会自动降级，所以要在保存后尽早暴露。
     */
    static String structuredCheck(HttpLLMAdapter adapter, ModelConfig config, LLMConfig base, Map<String, Object> detail) {
        String mode = StructuredOutputMode.effective(config);
        if (mode == null) return null;
        LLMConfig probe = new LLMConfig();
        probe.setProvider(base.getProvider()); probe.setApiFormat(base.getApiFormat()); probe.setModel(base.getModel());
        probe.setApiKey(base.getApiKey()); probe.setApiUrl(base.getApiUrl()); probe.setTemperature(0.0); probe.setMaxTokens(50);
        probe.setRequestTimeoutSeconds(60);   // 两次请求合计不超过前端 190 秒的等待
        probe.setResponseFormat(StructuredOutputMode.JSON_SCHEMA.equals(mode)
                ? io.yuan.octopus.review.dto.ResponseFormat.jsonSchema("octopus_probe", PROBE_SCHEMA)
                : io.yuan.octopus.review.dto.ResponseFormat.jsonObject());
        try {
            var response = adapter.call("You are a test harness.", "Return a JSON object whose field ok is true.", probe);
            var json = com.google.gson.JsonParser.parseString(response.content().strip());
            boolean ok = json.isJsonObject() && json.getAsJsonObject().has("ok") && json.getAsJsonObject().get("ok").isJsonPrimitive()
                    && json.getAsJsonObject().get("ok").getAsJsonPrimitive().isBoolean();
            detail.put("structuredOutput", ok ? "ACCEPTED" : "UNEXPECTED_CONTENT");
            return ok ? null : "连通正常，但结构化输出（" + mode + "）返回的内容不符合要求";
        } catch (com.google.gson.JsonParseException e) {
            detail.put("structuredOutput", "UNEXPECTED_CONTENT");
            return "连通正常，但结构化输出（" + mode + "）返回的不是 JSON";
        } catch (RuntimeException e) {
            detail.put("structuredOutput", "REJECTED");
            detail.put("structuredError", e.getMessage());
            return "连通正常，但端点不接受结构化输出（" + mode + "）：" + e.getMessage();
        }
    }

    public record ModelTestResult(boolean success, String message, int elapsedMs, Map<String, Object> detail) {}
}
