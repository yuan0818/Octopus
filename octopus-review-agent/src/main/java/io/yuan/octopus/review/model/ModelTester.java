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

            return new ModelTestResult(true, "OK (" + elapsed + "ms)", (int) elapsed, detail);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            detail.put("errorType", e.getClass().getSimpleName());
            detail.put("errorMessage", e.getMessage());
            return new ModelTestResult(false, e.getMessage(), (int) elapsed, detail);
        }
    }

    public record ModelTestResult(boolean success, String message, int elapsedMs, Map<String, Object> detail) {}
}
