package io.yuan.octopus.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.common.AuthType;
import io.yuan.octopus.domain.DiffResult;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.entity.RepoSnapshot;
import io.yuan.octopus.utils.CredentialEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Client for the octopus-git HTTP service.
 * <p>
 * When {@code octopus.git.service-url} is configured, delegates all Git
 * operations to the remote service via HTTP. When empty, falls back to
 * the embedded {@link GitService} for backward compatibility.
 * <p>
 * Maintains the same public API as {@link GitService} so callers need
 * no changes when switching modes.
 */
@Service
public class GitServiceClient {

    private static final Logger log = LoggerFactory.getLogger(GitServiceClient.class);

    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String serviceUrl;
    private final GitService localGitService;
    private final CredentialEncryptor credentialEncryptor;

    public GitServiceClient(
            @Value("${octopus.git.service-url:}") String serviceUrl,
            GitService localGitService,
            CredentialEncryptor credentialEncryptor) {
        this.serviceUrl = serviceUrl;
        this.localGitService = localGitService;
        this.credentialEncryptor = credentialEncryptor;
    }

    private boolean useRemote() {
        return serviceUrl != null && !serviceUrl.isBlank();
    }

    // ── Public API (same signatures as GitService) ────────────────────

    public RepoSnapshot cloneOrPull(RepoConfig repoConfig) {
        if (!useRemote()) return localGitService.cloneOrPull(repoConfig);
        return cloneOrPullRemote(repoConfig);
    }

    public RepoSnapshot syncAndCheckout(RepoConfig repoConfig, String tag) {
        if (!useRemote()) return localGitService.syncAndCheckout(repoConfig, tag);
        return syncAndCheckoutRemote(repoConfig, tag);
    }

    public List<String> listTags(RepoConfig repoConfig) {
        if (!useRemote()) return localGitService.listTags(repoConfig);
        return listTagsRemote(repoConfig);
    }

    public Optional<DiffResult> computeDiff(Path localPath, String fromTag, String toTag) {
        if (!useRemote()) return localGitService.computeDiff(localPath, fromTag, toTag);
        return computeDiffRemote(localPath, fromTag, toTag);
    }

    // ── Remote implementations ────────────────────────────────────────

    private RepoSnapshot cloneOrPullRemote(RepoConfig rc) {
        try {
            String body = gson.toJson(toConfigMap(rc));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl + "/api/git/sync"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("Git service sync returned {}: {}", resp.statusCode(), resp.body());
                throw new RuntimeException("Git sync failed: " + resp.body());
            }
            return parseSnapshot(resp.body());
        } catch (Exception e) {
            log.error("Git sync failed for {}: {}", rc.getRepoUrl(), e.getMessage());
            throw new RuntimeException("Git sync failed", e);
        }
    }

    private RepoSnapshot syncAndCheckoutRemote(RepoConfig rc, String tag) {
        try {
            Map<String, Object> reqMap = toConfigMap(rc);
            reqMap.put("tag", tag);
            String body = gson.toJson(reqMap);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl + "/api/git/checkout"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("Git service checkout returned {}: {}", resp.statusCode(), resp.body());
                throw new RuntimeException("Git checkout failed: " + resp.body());
            }
            return parseSnapshot(resp.body());
        } catch (Exception e) {
            log.error("Git checkout failed for {} tag {}: {}", rc.getRepoUrl(), tag, e.getMessage());
            throw new RuntimeException("Git checkout failed", e);
        }
    }

    private List<String> listTagsRemote(RepoConfig rc) {
        try {
            String repoName = GitService.extractRepoName(rc.getRepoUrl());
            String url = serviceUrl + "/api/git/" + repoName + "/tags?repoUrl=" +
                    java.net.URLEncoder.encode(rc.getRepoUrl(), StandardCharsets.UTF_8) +
                    "&branch=" + java.net.URLEncoder.encode(rc.getBranch(), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("Git service listTags returned {}", resp.statusCode());
                return List.of();
            }
            Type listType = new TypeToken<List<String>>() {}.getType();
            return gson.fromJson(resp.body(), listType);
        } catch (Exception e) {
            log.error("List tags failed for {}: {}", rc.getRepoUrl(), e.getMessage());
            return List.of();
        }
    }

    private Optional<DiffResult> computeDiffRemote(Path localPath, String fromTag, String toTag) {
        try {
            String repoName = localPath.getFileName().toString();
            Map<String, String> body = new LinkedHashMap<>();
            body.put("fromTag", fromTag != null ? fromTag : "");
            body.put("toTag", toTag);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl + "/api/git/" + repoName + "/diff"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) return Optional.empty();

            // Response is either null (empty Optional) or a DiffResult JSON
            if (resp.body() == null || resp.body().isBlank() || "null".equals(resp.body())) {
                return Optional.empty();
            }

            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> map = gson.fromJson(resp.body(), mapType);
            if (map == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            Set<String> changed = map.containsKey("changedFilePaths")
                    ? new HashSet<>((List<String>) map.get("changedFilePaths")) : Set.of();
            @SuppressWarnings("unchecked")
            Set<String> deleted = map.containsKey("deletedFilePaths")
                    ? new HashSet<>((List<String>) map.get("deletedFilePaths")) : Set.of();

            return Optional.of(new DiffResult(changed, deleted));
        } catch (Exception e) {
            log.warn("Remote diff failed ({} → {}): {}", fromTag, toTag, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Map<String, Object> toConfigMap(RepoConfig rc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("repoUrl", rc.getRepoUrl());
        map.put("branch", rc.getBranch());
        map.put("authType", rc.getAuthType().name());

        if (rc.getAuthType() == AuthType.HTTP) {
            map.put("httpUsername", rc.getHttpUsername());
            map.put("httpPassword", credentialEncryptor.decrypt(rc.getHttpCredentialEncrypted()));
        }
        if (rc.getAuthType() == AuthType.SSH) {
            map.put("sshPrivateKeyPath", rc.getSshPrivateKeyPath());
            if (rc.getSshPassphraseEncrypted() != null) {
                map.put("sshPassphrase", credentialEncryptor.decrypt(rc.getSshPassphraseEncrypted()));
            }
            map.put("sshKnownHostsPath", rc.getSshKnownHostsPath());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private RepoSnapshot parseSnapshot(String json) {
        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> map = gson.fromJson(json, mapType);
        return new RepoSnapshot(
                Path.of((String) map.get("repoLocalPath")),
                (String) map.get("repoUrl"),
                (String) map.get("commitId"),
                (String) map.get("tag"));
    }
}
