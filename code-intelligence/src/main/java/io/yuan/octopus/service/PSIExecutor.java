package io.yuan.octopus.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.domain.*;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes the IntelliJ PSI headless analyzer and deserializes its output
 * into {@link PSIAnalysisOutput}.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Subprocess mode</b> — spawns octopus-psi.jar as a one-shot process (legacy)</li>
 *   <li><b>HTTP mode</b> — calls a long-running octopus-psi HTTP service (new)</li>
 * </ul>
 * Mode is selected automatically: if {@code octopus.psi.service-url} is configured,
 * HTTP mode is used; otherwise subprocess mode.
 */
@Service
public class PSIExecutor {

    private static final Logger log = LoggerFactory.getLogger(PSIExecutor.class);

    private final Gson gson = new Gson();
    private final String psiJarPath;
    private final long timeoutSeconds;
    private final String psiServiceUrl;
    private final String hostWorkspaceRoot;
    /** 仓库存储根（容器侧路径，OCTOPUS_REPO_ROOT），与 hostRepoRoot 配对翻译。 */
    private final String repoBase;
    /** 仓库存储根在分析服务侧的实际路径；与 repoBase 一致（同径挂载）时置空禁用翻译。 */
    private final String hostRepoRoot;
    private final int connectRetryAttempts;
    private final long connectRetryIntervalSeconds;
    private final HttpClient httpClient;

    public PSIExecutor(@Value("${octopus.psi.jar-path:}") String psiJarPath,
                       @Value("${octopus.psi.timeout-seconds:600}") long timeoutSeconds,
                       @Value("${octopus.psi.service-url:}") String psiServiceUrl,
                       @Value("${octopus.psi.host-workspace-root:}") String hostWorkspaceRoot,
                       @Value("${octopus.git.local-repo-base:/data/repos}") String repoBase,
                       @Value("${octopus.psi.host-repo-root:}") String hostRepoRoot,
                       @Value("${octopus.psi.connect-retry-attempts:5}") int connectRetryAttempts,
                       @Value("${octopus.psi.connect-retry-interval-seconds:15}") long connectRetryIntervalSeconds) {
        this.psiJarPath = psiJarPath;
        this.timeoutSeconds = timeoutSeconds;
        this.psiServiceUrl = psiServiceUrl;
        this.hostWorkspaceRoot = hostWorkspaceRoot;
        this.hostRepoRoot = hostRepoRoot;
        this.repoBase = repoBase;
        this.connectRetryAttempts = connectRetryAttempts;
        this.connectRetryIntervalSeconds = connectRetryIntervalSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Run the PSI analyzer on the given project and return structured results.
     *
     * @param project the Java project to analyze
     * @return deserialized PSI analysis output, or {@code null} if PSI analysis is disabled or fails
     */
    public PSIAnalysisOutput execute(DiscoveredProject project) {
        try {
            PSIAnalysisOutput output = psiServiceUrl != null && !psiServiceUrl.isBlank()
                    ? executeViaHttp(project)
                    : executeViaSubprocess(project);
            if (!isUsable(output)) {
                log.warn("PSI output is empty or incomplete for project {}. Falling back to JavaParser-only mode.",
                        project.projectName());
                return null;
            }
            return output;
        } catch (RuntimeException e) {
            log.error("PSI output could not be consumed for project {}: {}. Falling back to JavaParser-only mode.",
                    project.projectName(), e.getMessage());
            return null;
        }
    }

    // ── HTTP mode ───────────────────────────────────────────────────

    /**
     * Sends analysis request to the PSI HTTP service, retrying on connection failures
     * (ConnectException) and 503 responses. IntelliJ Platform takes 30–120 seconds to
     * start, so the first few attempts may fail with ConnectException before the service
     * is ready. Timeouts are NOT retried — they indicate the analysis itself took too long.
     */
    private PSIAnalysisOutput executeViaHttp(DiscoveredProject project) {
        String projectDir = mapProjectDirForPsi(project.projectPath().toAbsolutePath().toString());
        String requestJson = gson.toJson(Map.of("projectDir", projectDir));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(psiServiceUrl + "/analyze"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .build();

        log.info("Sending PSI analysis request for project {} to {} (up to {} attempts)",
            project.projectName(), psiServiceUrl, connectRetryAttempts);

        for (int attempt = 1; attempt <= connectRetryAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 503) {
                    // Service starting up or busy — retry
                    log.warn("PSI service not ready (503) for project {} (attempt {}/{})",
                        project.projectName(), attempt, connectRetryAttempts);
                    if (!sleepForRetry(attempt, project.projectName())) return null;
                    continue;
                }

                if (response.statusCode() != 200) {
                    log.error("PSI service returned status {} for project {}: {}",
                        response.statusCode(), project.projectName(), response.body());
                    return null;
                }

                @SuppressWarnings("unchecked")
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> root = gson.fromJson(response.body(), mapType);

                boolean success = (boolean) root.getOrDefault("success", false);
                if (!success) {
                    log.error("PSI analysis failed for project {}: {}", project.projectName(), root.get("error"));
                    return null;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) root.get("data");
                return parseData(data);

            } catch (ConnectException e) {
                // Service not yet reachable — retry
                log.warn("PSI service not reachable at {} for project {} (attempt {}/{}): {}",
                    psiServiceUrl, project.projectName(), attempt, connectRetryAttempts, e.getMessage());
                if (!sleepForRetry(attempt, project.projectName())) return null;
            } catch (HttpTimeoutException e) {
                // Analysis ran but timed out — no point retrying
                log.error("PSI service timed out after {}s for project {}", timeoutSeconds, project.projectName());
                return null;
            } catch (IOException e) {
                log.error("HTTP I/O error during PSI analysis of project {}: {}", project.projectName(), e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("PSI HTTP request interrupted for project {}", project.projectName());
                return null;
            }
        }

        log.error("PSI service not reachable after {} attempts for project {}", connectRetryAttempts, project.projectName());
        return null;
    }

    /**
     * Sleeps between retry attempts. Returns {@code false} if this was the last attempt
     * or if the thread was interrupted (caller should abort and return null).
     */
    private boolean sleepForRetry(int attempt, String projectName) {
        if (attempt >= connectRetryAttempts) {
            return false;
        }
        log.info("Retrying PSI connection in {}s... (attempt {}/{})",
            connectRetryIntervalSeconds, attempt + 1, connectRetryAttempts);
        try {
            Thread.sleep(connectRetryIntervalSeconds * 1000L);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("PSI retry sleep interrupted for project {}", projectName);
            return false;
        }
    }

    /**
     * When API runs in Docker but PSI runs on host, translate container path (/workspace/...)
     * to host path (${octopus.psi.host-workspace-root}/...).
     */
    private String mapProjectDirForPsi(String projectDir) {
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

    @SuppressWarnings("unchecked")
    private PSIAnalysisOutput parseData(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        List<PSIMethodNode> methods = convertList(data.get("methods"), PSIMethodNode.class);
        List<PSICallEdge> edges = convertList(data.get("edges"), PSICallEdge.class);
        List<PSIBeanDefinition> beans = convertList(data.get("beans"), PSIBeanDefinition.class);
        List<PSIAOPBinding> aopBindings = convertList(data.get("aopBindings"), PSIAOPBinding.class);
        List<PSIEventBinding> eventBindings = convertList(data.get("eventBindings"), PSIEventBinding.class);
        List<PSIScheduledBinding> scheduled = convertList(data.get("scheduledBindings"), PSIScheduledBinding.class);
        List<PSIAsyncBinding> async = convertList(data.get("asyncBindings"), PSIAsyncBinding.class);
        List<PSIRepositoryBinding> repo = convertList(data.get("repositoryBindings"), PSIRepositoryBinding.class);

        return new PSIAnalysisOutput(methods, edges, beans, aopBindings, eventBindings, scheduled, async, repo);
    }

    private boolean isUsable(PSIAnalysisOutput output) {
        return output != null
                && output.methods() != null
                && !output.methods().isEmpty()
                && output.edges() != null
                && output.beans() != null
                && output.aopBindings() != null
                && output.eventBindings() != null
                && output.scheduledBindings() != null
                && output.asyncBindings() != null
                && output.repositoryBindings() != null;
    }

    // ── Subprocess mode (legacy) ────────────────────────────────────

    private PSIAnalysisOutput executeViaSubprocess(DiscoveredProject project) {
        if (psiJarPath == null || psiJarPath.isBlank()) {
            log.info("PSI analysis disabled (octopus.psi.jar-path not configured). Falling back to JavaParser-only mode.");
            return null;
        }

        Path psiJar = Path.of(psiJarPath);
        if (!Files.exists(psiJar)) {
            log.warn("PSI JAR not found at {}. Skipping PSI analysis.", psiJarPath);
            return null;
        }

        Path projectDir = project.projectPath();
        Path outputFile = createTempOutputFile(project);

        try {
            Process process = startSubprocess(psiJar, projectDir, outputFile);
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                log.error("PSI analysis timed out after {} seconds for project {}", timeoutSeconds, project.projectName());
                process.destroyForcibly();
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                log.error("PSI analysis failed with exit code {} for project {}: {}", exitCode, project.projectName(), stderr);
                return null;
            }

            if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
                log.error("PSI output file is missing or empty: {}", outputFile);
                return null;
            }

            String json = Files.readString(outputFile, StandardCharsets.UTF_8);
            return parseOutput(json);

        } catch (IOException e) {
            log.error("I/O error during PSI analysis of project {}: {}", project.projectName(), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("PSI analysis interrupted for project {}", project.projectName());
            return null;
        } finally {
            cleanup(outputFile);
        }
    }

    private Process startSubprocess(Path psiJar, Path projectDir, Path outputFile) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-jar", psiJar.toAbsolutePath().toString(),
            "analyze",
            "--project-dir", projectDir.toAbsolutePath().toString(),
            "--output", outputFile.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(false);
        log.info("Starting PSI analyzer: {}", String.join(" ", pb.command()));
        return pb.start();
    }

    private Path createTempOutputFile(DiscoveredProject project) {
        try {
            return Files.createTempFile("octopus-psi-" + sanitize(project.projectName()) + "-", ".json");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp file for PSI output", e);
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @SuppressWarnings("unchecked")
    private PSIAnalysisOutput parseOutput(String json) {
        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> root = gson.fromJson(json, mapType);
        return parseData(root);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> convertList(Object obj, Class<T> clazz) {
        if (obj == null) return List.of();
        String json = gson.toJson(obj);
        Type listType = TypeToken.getParameterized(List.class, clazz).getType();
        return gson.fromJson(json, listType);
    }

    private void cleanup(Path outputFile) {
        try {
            Files.deleteIfExists(outputFile);
        } catch (IOException e) {
            log.debug("Failed to delete temp PSI output file: {}", outputFile);
        }
    }
}
