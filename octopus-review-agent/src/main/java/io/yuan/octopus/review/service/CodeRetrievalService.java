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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client for calling the code-reconstruct service.
 * <p>
 * 透传 {@code branch} 和 {@code commitId} 参数到 reconstruct 服务，
 * 用于版本化源码获取。
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
     * <p>
     * 可选参数 {@code branch}、{@code commitId} 透传到 reconstruct 服务。
     */
    public Map<String, Object> getChainMetadata(Long projectId, String className,
                                                  String methodName, String strategy,
                                                  String branch, String commitId) {
        StringBuilder url = new StringBuilder(
                "%s/api/chain-metadata?projectId=%d&className=%s&methodName=%s&strategy=%s"
                        .formatted(baseUrl, projectId,
                                URLEncoder.encode(className, StandardCharsets.UTF_8),
                                URLEncoder.encode(methodName, StandardCharsets.UTF_8),
                                URLEncoder.encode(strategy != null ? strategy : "BFS", StandardCharsets.UTF_8)));
        if (branch != null && !branch.isBlank()) {
            url.append("&branch=").append(URLEncoder.encode(branch, StandardCharsets.UTF_8));
        }
        if (commitId != null && !commitId.isBlank()) {
            url.append("&commitId=").append(URLEncoder.encode(commitId, StandardCharsets.UTF_8));
        }

        try {
            String json = client.send(
                    HttpRequest.newBuilder(URI.create(url.toString()))
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
     * <p>
     * 可选参数 {@code branch}、{@code commitId} 透传到 reconstruct 请求体。
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getSourceCode(Long projectId, String className,
                                               String methodName, String strategy,
                                               String branch, String commitId) {
        String url = baseUrl + "/api/reconstruct";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("className", className);
        body.put("methodName", methodName);
        body.put("strategy", strategy);
        if (branch != null && !branch.isBlank()) {
            body.put("branch", branch);
        }
        if (commitId != null && !commitId.isBlank()) {
            body.put("commitId", commitId);
        }

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
