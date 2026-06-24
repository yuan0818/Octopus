package io.yuan.octopus.review.adapter;

import io.yuan.octopus.review.dto.LLMConfig;

/**
 * Adapter interface for multi-provider LLM integration.
 */
public interface LLMAdapter {

    /** Provider identifier, e.g. "claude", "openai" */
    String provider();

    /** Call the LLM with the given prompt and config. */
    LLMResponse call(String systemPrompt, String userPrompt, LLMConfig config);

    /** Whether this adapter is configured and ready. */
    boolean isAvailable();

    record LLMResponse(
            String content,
            String model,
            int promptTokens,
            int completionTokens
    ) {}
}
