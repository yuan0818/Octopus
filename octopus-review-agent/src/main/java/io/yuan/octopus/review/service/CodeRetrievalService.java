package io.yuan.octopus.review.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.infra.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client for calling the code-reconstruct service.
 */
@Service
public class CodeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(CodeRetrievalService.class);

    private final String baseUrl;
    private final HttpClient client;
    private final Gson gson;

    public CodeRetrievalService(
            @Value("${review.reconstruct.url}") String baseUrl,
            @Value("${review.reconstruct.connect-timeout:5000}") int connectTimeout,
            @Value("${review.reconstruct.read-timeout:30000}") int readTimeout) {
        this.baseUrl = baseUrl;
        this.gson = new Gson();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
    }

    /**
     * Get the call chain structure tree (signatures only, no source).
     */
    public Map<String, Object> getChainMetadata(Long projectId, String className,
                                                  String methodName, String strategy) {
        String url = "%s/api/chain-metadata?projectId=%d&className=%s&methodName=%s&strategy=%s"
                .formatted(baseUrl, projectId,
                        URLEncoder.encode(className, StandardCharsets.UTF_8),
                        URLEncoder.encode(methodName, StandardCharsets.UTF_8),
                        URLEncoder.encode(strategy != null ? strategy : "BFS", StandardCharsets.UTF_8));
        try {
            String json = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Result<Map<String, Object>> result = gson.fromJson(json,
                    new TypeToken<Result<LinkedHashMap>>() {}.getType());
            return result.data();
        } catch (Exception e) {
            log.error("Failed to get chain metadata: {}", e.getMessage());
            throw new RuntimeException("Reconstruct service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Get full source code for the call chain.
     * Returns Map<className, sourceCode>.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getSourceCode(Long projectId, String className,
                                               String methodName, String strategy) {
        String url = baseUrl + "/api/reconstruct";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("className", className);
        body.put("methodName", methodName);
        body.put("strategy", strategy);

        try {
            String json = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(60))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            Result<Map<String, Object>> result = gson.fromJson(json,
                    new TypeToken<Result<LinkedHashMap>>() {}.getType());
            if (result.data() == null || result.code() != 200) {
                // No call chain data — method may have no downstream calls.
                // Return empty source map; the caller (ReviewOrchestrator) will
                // still present what it can to the LLM.
                log.warn("No source code data from reconstruct for {}.{}: {}",
                        className, methodName, result.message());
                return Map.of(className, "源码不可用（方法无调用链数据）");
            }
            String classesJson = gson.toJson(result.data().get("classes"));
            return gson.fromJson(classesJson, new TypeToken<Map<String, String>>() {}.getType());
        } catch (Exception e) {
            log.error("Failed to get source code: {}", e.getMessage());
            throw new RuntimeException("Reconstruct service unavailable: " + e.getMessage(), e);
        }
    }
}
