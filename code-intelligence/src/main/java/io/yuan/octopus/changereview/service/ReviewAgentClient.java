package io.yuan.octopus.changereview.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.yuan.octopus.changereview.dto.EndpointReviewResult;
import io.yuan.octopus.changereview.dto.ReviewIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client to the {@code octopus-review-agent} service's {@code POST /api/review}
 * (REQ-CTR-007). Calls one affected endpoint per request with the merge commit as
 * version anchor; returns a lightweight {@link EndpointReviewResult} capturing the
 * session id and findings for change-level aggregation.
 *
 * <p>Per BR-CTR-035 a single endpoint's review failure must not block the pipeline —
 * failures surface as {@link EndpointReviewResult#failed()} and the orchestrator
 * records them in the report without throwing.
 */
@Component
public class ReviewAgentClient {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgentClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    private final String baseUrl;
    private final int timeoutSeconds;
    private final String defaultSkillId;

    public ReviewAgentClient(@Value("${octopus.review-agent.service-url:http://localhost:8083}") String baseUrl,
                             @Value("${octopus.change-review.review-endpoint-timeout-seconds:120}") int timeoutSeconds,
                             @Value("${octopus.change-review.default-skill-id:code-review}") String defaultSkillId) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeoutSeconds = timeoutSeconds;
        this.defaultSkillId = defaultSkillId;
    }

    /**
     * Synchronously review one affected endpoint against the merge version.
     *
     * @param projectId   owning project id
     * @param className   endpoint controller FQN
     * @param methodName  endpoint method name
     * @param branch      target branch
     * @param commitId    merge commit SHA (version anchor)
     * @return review result (never throws for review-level failures; throws only on
     *         unrecoverable transport errors, which the orchestrator treats as
     *         a failed endpoint)
     */
    public EndpointReviewResult reviewEndpoint(Long projectId, String className, String methodName,
                                               String branch, String commitId) {
        JsonObject body = new JsonObject();
        body.addProperty("projectId", projectId);
        body.addProperty("className", className);
        body.addProperty("methodName", methodName);
        body.addProperty("strategy", "BFS");
        body.addProperty("branch", branch);
        body.addProperty("commitId", commitId);
        body.addProperty("reviewMode", "RISK_BASED");
        body.addProperty("mode", "SYNC");
        body.addProperty("skillId", defaultSkillId);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/review"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(response, className, methodName);
        } catch (Exception e) {
            log.warn("Review-agent call failed for {}#{} (commitId={}): {}", className, methodName, commitId, e.getMessage());
            return new EndpointReviewResult(null, "FAILED", null, List.of(),
                    "Review agent unavailable: " + e.getMessage());
        }
    }

    private EndpointReviewResult parseResponse(HttpResponse<String> response, String className, String methodName) {
        if (response.statusCode() >= 400) {
            return new EndpointReviewResult(null, "FAILED", null, List.of(),
                    "Review agent HTTP " + response.statusCode() + " for " + className + "#" + methodName);
        }
        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            // Result wrapper: { code, message, success, data }
            if (root.has("success") && !root.get("success").getAsBoolean()) {
                String msg = root.has("message") ? root.get("message").getAsString() : "review-agent reported failure";
                return new EndpointReviewResult(null, "FAILED", null, List.of(), msg);
            }
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : root;

            String sessionId = optString(data, "sessionId");
            JsonObject summary = data.has("summary") && data.get("summary").isJsonObject()
                    ? data.getAsJsonObject("summary") : new JsonObject();
            String status = optString(summary, "status");
            String topSeverity = optString(summary, "topSeverity");

            List<ReviewIssue> issues = new ArrayList<>();
            if (data.has("issues") && data.get("issues").isJsonArray()) {
                JsonArray arr = data.getAsJsonArray("issues");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject issue = el.getAsJsonObject();
                    issues.add(new ReviewIssue(
                            optString(issue, "severity"),
                            optString(issue, "category"),
                            optString(issue, "description"),
                            optString(issue, "suggestion")));
                }
            }
            return new EndpointReviewResult(sessionId, status, topSeverity, issues, null);
        } catch (Exception e) {
            log.warn("Failed to parse review-agent response for {}#{}: {}", className, methodName, e.getMessage());
            return new EndpointReviewResult(null, "FAILED", null, List.of(),
                    "Review agent response parse error: " + e.getMessage());
        }
    }

    private String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }
}
