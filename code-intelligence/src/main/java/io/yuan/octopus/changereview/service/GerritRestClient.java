package io.yuan.octopus.changereview.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.yuan.octopus.changereview.dto.PatchsetInfo;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.infra.common.CredentialEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Gerrit REST client (REQ-CTR-008 / REQ-CTR-001 manual trigger). Uses HTTP Basic
 * auth with the credentials stored encrypted on {@link RepoConfig#getPlatformTokenEncrypted()}
 * (decrypted here via {@link CredentialEncryptor}), independent of git-transport
 * auth (BR-CTR-041).
 *
 * <p>Gerrit REST responses are XSS-prefixed ({@code )]}'}) and must be stripped
 * before parsing. Comment writeback goes to {@code POST /a/changes/{change}/revisions/current/review}.
 */
@Component
public class GerritRestClient {

    private static final Logger log = LoggerFactory.getLogger(GerritRestClient.class);

    private static final String XSSI_PREFIX = ")]}'";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final CredentialEncryptor credentialEncryptor;

    public GerritRestClient(CredentialEncryptor credentialEncryptor) {
        this.credentialEncryptor = credentialEncryptor;
    }

    /**
     * Resolves the latest patchset of a Change (manual trigger path, SCN-005).
     */
    public PatchsetInfo getLatestPatchset(RepoConfig repoConfig, String change) {
        String url = baseUrl(repoConfig) + "/a/changes/" + urlEncode(change) + "?o=ALL_REVISIONS";
        try {
            HttpResponse<String> resp = sendGet(url, repoConfig);
            if (resp.statusCode() >= 400) {
                throw new GerritRestException("Gerrit getLatestPatchset HTTP " + resp.statusCode() + " for change " + change);
            }
            JsonObject changeJson = parseGerritJson(resp.body());
            String branch = optString(changeJson, "branch");
            String currentRevision = optString(changeJson, "current_revision");
            if (currentRevision == null) {
                throw new GerritRestException("No current_revision for change " + change);
            }
            JsonObject revisions = changeJson.has("revisions") && changeJson.get("revisions").isJsonObject()
                    ? changeJson.getAsJsonObject("revisions") : null;
            int patchsetNumber = 0;
            if (revisions != null && revisions.has(currentRevision)) {
                JsonObject rev = revisions.getAsJsonObject(currentRevision);
                JsonElement num = rev.get("_number");
                if (num != null && num.isJsonPrimitive()) {
                    patchsetNumber = num.getAsInt();
                }
            }
            if (patchsetNumber <= 0) {
                // 显式报错：静默返回 0 会让下游 fetch refs/changes/../0 才失败，难以定位
                throw new GerritRestException("Cannot resolve patchset number for change " + change
                        + " revision " + currentRevision);
            }
            return new PatchsetInfo(patchsetNumber, branch, currentRevision);
        } catch (GerritRestException e) {
            throw e;
        } catch (Exception e) {
            throw new GerritRestException("Gerrit getLatestPatchset failed for change " + change + ": " + e.getMessage(), e);
        }
    }

    /**
     * Posts a change-level review comment. Returns {@code true} on success.
     * Failure is non-fatal — the orchestrator marks the task PARTIALLY_COMPLETED
     * and the result is retained in Octopus DB (BR-CTR-042).
     */
    public boolean postComment(RepoConfig repoConfig, String change, String body) {
        String url = baseUrl(repoConfig) + "/a/changes/" + urlEncode(change) + "/revisions/current/review";
        JsonObject payload = new JsonObject();
        payload.addProperty("message", body);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", basicAuth(repoConfig))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                log.warn("Gerrit postComment failed (HTTP {}) for change {}", resp.statusCode(), change);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Gerrit postComment exception for change {}: {}", change, e.getMessage());
            return false;
        }
    }

    // ── Helpers ──

    private HttpResponse<String> sendGet(String url, RepoConfig repoConfig) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", basicAuth(repoConfig))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String basicAuth(RepoConfig repoConfig) {
        String token = credentialEncryptor.decrypt(repoConfig.getPlatformTokenEncrypted());
        // token stored as "username:http_password"
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static String baseUrl(RepoConfig repoConfig) {
        if (repoConfig.getPlatformUrl() == null || repoConfig.getPlatformUrl().isBlank()) {
            throw new GerritRestException("RepoConfig " + repoConfig.getId() + " has no platform_url configured");
        }
        return repoConfig.getPlatformUrl().replaceAll("/+$", "");
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonObject parseGerritJson(String body) {
        String json = body;
        if (json != null && json.startsWith(XSSI_PREFIX)) {
            json = json.substring(XSSI_PREFIX.length()).trim();
        }
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    /** Thrown when Gerrit REST call fails (non-fatal at orchestrator level). */
    public static class GerritRestException extends RuntimeException {
        public GerritRestException(String message) { super(message); }
        public GerritRestException(String message, Throwable cause) { super(message, cause); }
    }
}
