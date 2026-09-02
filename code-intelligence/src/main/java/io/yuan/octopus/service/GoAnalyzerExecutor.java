package io.yuan.octopus.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.domain.GoAnalysisOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Executes the octopus go-project-analyzer HTTP service and deserializes its
 * output into {@link GoAnalysisOutput}. Modeled on {@link PSIExecutor}'s HTTP
 * mode: retries on connection failures (the Go service takes a few seconds to
 * boot), no retry on analysis timeouts.
 */
@Service
public class GoAnalyzerExecutor {

    private static final Logger log = LoggerFactory.getLogger(GoAnalyzerExecutor.class);

    private final Gson gson = new Gson();
    private final String serviceUrl;
    private final long timeoutSeconds;
    private final String hostWorkspaceRoot;
    private final int connectRetryAttempts;
    private final long connectRetryIntervalSeconds;
    private final HttpClient httpClient;

    public GoAnalyzerExecutor(@Value("${octopus.go.service-url:http://localhost:8378}") String serviceUrl,
                              @Value("${octopus.go.timeout-seconds:600}") long timeoutSeconds,
                              @Value("${octopus.go.host-workspace-root:}") String hostWorkspaceRoot,
                              @Value("${octopus.go.connect-retry-attempts:3}") int connectRetryAttempts,
                              @Value("${octopus.go.connect-retry-interval-seconds:5}") long connectRetryIntervalSeconds) {
        this.serviceUrl = serviceUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.hostWorkspaceRoot = hostWorkspaceRoot;
        this.connectRetryAttempts = connectRetryAttempts;
        this.connectRetryIntervalSeconds = connectRetryIntervalSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Runs the Go analyzer on the given workspace root.
     *
     * @return deserialized output, or {@code null} if the service is disabled
     *         or the analysis fails (caller marks the modules degraded)
     */
    public GoAnalysisOutput execute(Path projectRoot) {
        if (serviceUrl == null || serviceUrl.isBlank()) {
            log.info("Go analyzer disabled (octopus.go.service-url not configured).");
            return null;
        }
        String projectDir = mapProjectDirForService(projectRoot.toAbsolutePath().toString());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/analyze"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("projectDir", projectDir))))
                .build();

        log.info("Sending Go analysis request for {} to {} (up to {} attempts)",
                projectDir, serviceUrl, connectRetryAttempts);

        for (int attempt = 1; attempt <= connectRetryAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 503) {
                    log.warn("Go analyzer not ready (503) (attempt {}/{})", attempt, connectRetryAttempts);
                    if (!sleepForRetry(attempt)) return null;
                    continue;
                }
                if (response.statusCode() != 200) {
                    log.error("Go analyzer returned status {}: {}", response.statusCode(), response.body());
                    return null;
                }

                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> root = gson.fromJson(response.body(), mapType);
                if (!Boolean.TRUE.equals(root.get("success"))) {
                    log.error("Go analysis failed: {}", root.get("error"));
                    return null;
                }
                GoAnalysisOutput output = gson.fromJson(
                        gson.toJsonTree(root.get("data")), GoAnalysisOutput.class);
                if (output == null || output.methods() == null || output.methods().isEmpty()) {
                    log.warn("Go analysis output has no methods for {}; treating as unusable.", projectDir);
                    return null;
                }
                // Older analyzer builds may emit null for empty lists (e.g. a
                // workspace without named types); normalize before consumption.
                return new GoAnalysisOutput(
                        output.types() != null ? output.types() : List.of(),
                        output.methods(),
                        output.edges() != null ? output.edges() : List.of(),
                        output.httpRoutes() != null ? output.httpRoutes() : List.of());
            } catch (ConnectException e) {
                log.warn("Go analyzer not reachable at {} (attempt {}/{}): {}",
                        serviceUrl, attempt, connectRetryAttempts, e.getMessage());
                if (!sleepForRetry(attempt)) return null;
            } catch (HttpTimeoutException e) {
                log.error("Go analysis timed out after {}s for {}", timeoutSeconds, projectDir);
                return null;
            } catch (IOException e) {
                log.error("HTTP I/O error during Go analysis of {}: {}", projectDir, e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Go analysis HTTP request interrupted for {}", projectDir);
                return null;
            }
        }
        log.error("Go analyzer not reachable after {} attempts for {}", connectRetryAttempts, projectDir);
        return null;
    }

    private boolean sleepForRetry(int attempt) {
        if (attempt >= connectRetryAttempts) {
            return false;
        }
        try {
            Thread.sleep(connectRetryIntervalSeconds * 1000L);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * When the API runs in Docker but the Go analyzer runs on the host,
     * translate the container path prefix to the host workspace root
     * (same mapping as PSIExecutor).
     */
    private String mapProjectDirForService(String projectDir) {
        if (hostWorkspaceRoot == null || hostWorkspaceRoot.isBlank()) {
            return projectDir;
        }
        String containerPrefix = "/workspace";
        if (!projectDir.equals(containerPrefix) && !projectDir.startsWith(containerPrefix + "/")) {
            return projectDir;
        }
        String suffix = projectDir.substring(containerPrefix.length());
        String root = hostWorkspaceRoot.endsWith("/")
                ? hostWorkspaceRoot.substring(0, hostWorkspaceRoot.length() - 1)
                : hostWorkspaceRoot;
        return root + suffix;
    }
}
