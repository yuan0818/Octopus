package io.yuan.octopus.review.service;

import io.yuan.octopus.review.adapter.LLMAdapter;
import io.yuan.octopus.review.adapter.LLMAdapter.LLMResponse;
import io.yuan.octopus.review.dto.LLMConfig;
import io.yuan.octopus.review.model.ModelConfig;
import io.yuan.octopus.review.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Multi-provider LLM integration service.
 * Uses ModelRegistry for model selection and fallback.
 */
@Service
public class LLMIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(LLMIntegrationService.class);

    private final List<LLMAdapter> adapters;
    private final ModelRegistry modelRegistry;

    public LLMIntegrationService(List<LLMAdapter> adapters, ModelRegistry modelRegistry) {
        this.adapters = adapters;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Call the LLM with skill-aware model selection.
     * Uses the Skill's preferred provider with fallback chain.
     */
    public LLMResponse call(String systemPrompt, String userPrompt, LLMConfig config) {
        String provider = config.getProvider() != null ? config.getProvider() : "unknown";
        String modelName = config.getModel() != null ? config.getModel() : provider;

        LLMAdapter adapter = findAdapter(provider);
        if (adapter == null) {
            throw new RuntimeException("没有可用的 LLM adapter");
        }

        // Primary attempt
        try {
            LLMResponse response = adapter.call(systemPrompt, userPrompt, config);
            modelRegistry.recordSuccess(modelName);
            return response;
        } catch (Exception e) {
            log.warn("LLM call failed for {} (will retry): {}", modelName, e.getMessage());
            modelRegistry.recordFailure(modelName);
        }

        // Retry once with fallback model if available
        ModelConfig fallback = modelRegistry.selectWithFallback(provider);
        if (fallback != null && !fallback.getName().equals(modelName)) {
            log.info("Falling back to model: {}", fallback.getName());
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            LLMConfig fallbackConfig = new LLMConfig();
            fallbackConfig.setProvider(fallback.getProvider());
            fallbackConfig.setModel(fallback.getModelId());
            fallbackConfig.setApiKey(fallback.getApiKey());
            fallbackConfig.setApiUrl(fallback.getApiUrl());
            fallbackConfig.setMaxTokens(fallback.getMaxTokens());
            fallbackConfig.setTemperature(fallback.getTemperature());
            return adapter.call(systemPrompt, userPrompt, fallbackConfig);
        }

        // Final retry with same config
        return adapter.call(systemPrompt, userPrompt, config);
    }

    private LLMAdapter findAdapter(String provider) {
        return adapters.stream()
                .filter(LLMAdapter::isAvailable)
                .findFirst()
                .orElse(null);
    }
}
