package io.yuan.octopus.psi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded HTTP server that wraps PSI analysis as a long-running service.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /health}     — liveness/readiness probe</li>
 *   <li>{@code POST /analyze}   — run analysis on a project directory</li>
 *   <li>{@code POST /shutdown}  — graceful shutdown</li>
 * </ul>
 * <p>
 * All PSI-intensive work is executed on a single-threaded executor to avoid
 * concurrency issues with IntelliJ Platform internals.
 */
public class AnalysisHttpServer {

    private static final Logger LOG = Logger.getInstance(AnalysisHttpServer.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 1500; // 25 min: covers 1200s indexing + analysis overhead

    private final HttpServer server;
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ProjectBootstrap projectBootstrap;
    private final long startTimeMillis = System.currentTimeMillis();

    public AnalysisHttpServer(int port, ProjectBootstrap projectBootstrap) throws IOException {
        this.projectBootstrap = projectBootstrap;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(httpExecutor);

        server.createContext("/health", this::handleHealth);
        server.createContext("/analyze", this::handleAnalyze);
        server.createContext("/shutdown", this::handleShutdown);
    }

    public void start() {
        server.start();
    }

    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
        analysisExecutor.shutdownNow();
        httpExecutor.shutdownNow();
    }

    // ── Health ──────────────────────────────────────────────────────

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", running.get() ? "ok" : "shutting_down");
        body.put("uptime", (System.currentTimeMillis() - startTimeMillis) / 1000);
        sendJson(exchange, 200, body);
    }

    // ── Analyze ─────────────────────────────────────────────────────

    private void handleAnalyze(HttpExchange exchange) throws IOException {
        if (!running.get()) {
            sendJson(exchange, 503, Map.of("error", "Service is shutting down"));
            return;
        }

        String requestBody;
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = gson.fromJson(requestBody, Map.class);

        String projectDirStr = (String) requestMap.get("projectDir");
        if (projectDirStr == null || projectDirStr.isBlank()) {
            sendJson(exchange, 400, Map.of("success", false, "error", "Missing required field: projectDir"));
            return;
        }

        Path projectDir = Path.of(projectDirStr);
        if (!Files.isDirectory(projectDir)) {
            sendJson(exchange, 400, Map.of("success", false, "error", "Project directory does not exist: " + projectDirStr));
            return;
        }

        String outputPathStr = (String) requestMap.get("outputPath");

        // Submit analysis to the single-threaded executor
        LOG.info("Analysis request received for project: " + projectDirStr);
        Future<Map<String, Object>> future = analysisExecutor.submit(() -> {
            // Clear any stale interrupt flag left by a previous cancelled task.
            // Without this, the single-threaded executor re-uses the interrupted
            // thread and the very first blocking call (e.g. invokeAndWait inside
            // openProject) would immediately throw InterruptedException, causing
            // every analysis after a timeout to fail silently.
            if (Thread.interrupted()) {
                LOG.warn("Cleared stale interrupt flag from previously cancelled task");
            }

            Project project = null;
            Map<String, Object> result = new LinkedHashMap<>();
            List<String> warnings = new java.util.ArrayList<>();

            try {
                project = projectBootstrap.openProject(projectDir);
                projectBootstrap.waitForIndexing(project);

                LOG.info("Starting PSI extraction for project: " + projectDirStr);
                Map<String, Object> data = PsiAnalyzerMain.extractProject(project);
                LOG.info("PSI extraction complete for project: " + projectDirStr);

                // Write to output file if requested
                if (outputPathStr != null && !outputPathStr.isBlank()) {
                    Path outputPath = Path.of(outputPathStr);
                    if (outputPath.getParent() != null) {
                        Files.createDirectories(outputPath.getParent());
                    }
                    String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(data);
                    Files.writeString(outputPath, json, StandardCharsets.UTF_8);
                }

                return Map.of("success", true, "data", data, "warnings", warnings);

            } catch (InterruptedException e) {
                LOG.warn("Analysis interrupted for project: " + projectDirStr);
                // Do NOT re-interrupt: this thread is reused by the single-threaded executor.
                // Re-setting the interrupt flag here would poison the next submitted task
                // (e.g. closeProject's invokeAndWait would immediately fail).
                return Map.of("success", false, "error", "Analysis interrupted (indexing or shutdown)");
            } catch (Exception e) {
                LOG.error("Analysis failed for project " + projectDirStr + ": " + e.getMessage(), e);
                return Map.of("success", false, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (project != null) {
                    try {
                        projectBootstrap.closeProject(project);
                    } catch (Exception e) {
                        LOG.warn("Failed to close project " + projectDirStr + ": " + e.getMessage());
                    }
                }
            }
        });

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            safeSendJson(exchange, 200, response);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            LOG.warn("Analysis timed out after " + DEFAULT_TIMEOUT_SECONDS + "s for project: " + projectDirStr);
            safeSendJson(exchange, 504, Map.of("success", false, "error", "Analysis timed out after " + DEFAULT_TIMEOUT_SECONDS + "s"));
        } catch (Exception e) {
            safeSendJson(exchange, 500, Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── Shutdown ────────────────────────────────────────────────────

    private void handleShutdown(HttpExchange exchange) throws IOException {
        LOG.info("Shutdown request received.");
        running.set(false);
        sendJson(exchange, 200, Map.of("message", "shutting down"));
        shutdownLatch.countDown();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        String json = gson.toJson(body);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Same as {@link #sendJson} but swallows any IOException.
     * Used in error-response paths where the client may have already disconnected
     * (e.g. after a timeout or 5xx), making {@code sendResponseHeaders} or
     * {@code getResponseBody} prone to throw.
     */
    private void safeSendJson(HttpExchange exchange, int statusCode, Object body) {
        try {
            sendJson(exchange, statusCode, body);
        } catch (IOException e) {
            LOG.warn("Failed to send error response (client may have disconnected): " + e.getMessage());
        }
    }
}
