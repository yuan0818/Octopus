package io.yuan.octopus.psi;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless entry point for the Octopus PSI analyzer.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>One-shot mode</b> (existing): {@code analyze --project-dir &lt;path&gt; --output &lt;path&gt;}</li>
 *   <li><b>Service mode</b> (new): {@code analyze serve --port &lt;port&gt;}</li>
 * </ul>
 * <p>
 * This class implements {@link ApplicationStarter} so that the IntelliJ Platform
 * can bootstrap it in headless mode.
 */
public class PsiAnalyzerMain implements ApplicationStarter {

    private static final Logger LOG = Logger.getInstance(PsiAnalyzerMain.class);

    private static final String PROJECT_DIR_ARG = "--project-dir";
    private static final String OUTPUT_ARG = "--output";
    private static final String PORT_ARG = "--port";

    @Override
    public String getCommandName() {
        return "analyze";
    }

    @Override
    public boolean isHeadless() {
        return true;
    }

    @Override
    public int getRequiredModality() {
        return NOT_IN_EDT;
    }

    @Override
    public void main(List<String> args) {
        // IntelliJ passes the command name ("analyze") as args[0] — skip it.
        List<String> rest = !args.isEmpty() && "analyze".equals(args.get(0))
                ? args.subList(1, args.size()) : args;

        if (!rest.isEmpty() && "serve".equals(rest.get(0))) {
            startService(rest.subList(1, rest.size()));
            return;
        }
        runOneShotAnalysis(rest);
    }

    // ── Service mode ────────────────────────────────────────────────

    private void startService(List<String> args) {
        int port = 8377;
        for (int i = 0; i < args.size(); i++) {
            if (PORT_ARG.equals(args.get(i)) && i + 1 < args.size()) {
                port = Integer.parseInt(args.get(++i));
            }
        }

        LOG.info("Starting PSI Analysis HTTP service on port " + port);
        LOG.info("Endpoints: POST /analyze, GET /health, POST /shutdown");

        ProjectBootstrap bootstrap = new ProjectBootstrap();
        AnalysisHttpServer server;
        try {
            server = new AnalysisHttpServer(port, bootstrap);
        } catch (IOException e) {
            LOG.error("Failed to start HTTP server: " + e.getMessage(), e);
            System.exit(1);
            return;
        }
        server.start();

        // Block the main thread until shutdown is requested.
        // When this returns, the IntelliJ Platform exits.
        try {
            server.awaitShutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.stop(5);
            LOG.info("PSI Analysis HTTP service stopped.");
        }
    }

    // ── One-shot mode ───────────────────────────────────────────────

    private void runOneShotAnalysis(List<String> args) {
        Path projectDir = null;
        Path outputPath = null;

        for (int i = 0; i < args.size(); i++) {
            if (PROJECT_DIR_ARG.equals(args.get(i)) && i + 1 < args.size()) {
                projectDir = Path.of(args.get(++i));
            } else if (OUTPUT_ARG.equals(args.get(i)) && i + 1 < args.size()) {
                outputPath = Path.of(args.get(++i));
            }
        }

        if (projectDir == null || outputPath == null) {
            LOG.error("Usage: analyze --project-dir <path> --output <path>");
            System.exit(1);
            return;
        }

        if (!Files.isDirectory(projectDir)) {
            LOG.error("Project directory does not exist: " + projectDir);
            System.exit(1);
            return;
        }

        try {
            ProjectBootstrap bootstrap = new ProjectBootstrap();
            Project project = bootstrap.openProject(projectDir);
            try {
                bootstrap.waitForIndexing(project);

                Map<String, Object> result = extractProject(project);

                JSONOutputWriter writer = new JSONOutputWriter();
                writer.write(outputPath,
                    new CallGraphResult(
                        (List<Map<String, Object>>) result.get("methods"),
                        (List<Map<String, Object>>) result.get("edges")),
                    (List<Map<String, Object>>) result.get("beans"),
                    (List<Map<String, Object>>) result.get("aopBindings"),
                    (List<Map<String, Object>>) result.get("eventBindings"),
                    (List<Map<String, Object>>) result.get("scheduledBindings"),
                    (List<Map<String, Object>>) result.get("asyncBindings"),
                    (List<Map<String, Object>>) result.get("repositoryBindings"));

                LOG.info("Analysis complete. Output written to: " + outputPath);

            } finally {
                ProjectUtil.closeAndDispose(project);
            }
        } catch (Exception e) {
            LOG.error("Analysis failed: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    // ── Shared analysis pipeline ────────────────────────────────────

    /**
     * Runs the full extraction pipeline against the given project.
     * The project must be already opened and indexed.
     *
     * @return structured map with keys: methods, edges, beans, aopBindings,
     *         eventBindings, scheduledBindings, asyncBindings, repositoryBindings
     */
    static Map<String, Object> extractProject(Project project) {
        return ReadAction.compute(() -> {
            SpringModelExtractor springExtractor = new SpringModelExtractor(project);
            CallGraphExtractor callGraphExtractor = new CallGraphExtractor(project, springExtractor);
            CallGraphResult callGraphResult = callGraphExtractor.extract();

            AOPExtractor aopExtractor = new AOPExtractor(project);
            List<Map<String, Object>> aopBindings = aopExtractor.extract();

            EventExtractor eventExtractor = new EventExtractor(project);
            List<Map<String, Object>> eventBindings = eventExtractor.extract();

            RepositoryExtractor repoExtractor = new RepositoryExtractor(project);
            List<Map<String, Object>> repoBindings = repoExtractor.extract();

            ScheduledExtractor scheduledExtractor = new ScheduledExtractor(project);
            List<Map<String, Object>> scheduledBindings = scheduledExtractor.extract();

            AsyncExtractor asyncExtractor = new AsyncExtractor(project);
            List<Map<String, Object>> asyncBindings = asyncExtractor.extract();

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("methods", callGraphResult.methods());
            root.put("edges", callGraphResult.edges());
            root.put("beans", springExtractor.getBeanDefinitions());
            root.put("aopBindings", aopBindings);
            root.put("eventBindings", eventBindings);
            root.put("scheduledBindings", scheduledBindings);
            root.put("asyncBindings", asyncBindings);
            root.put("repositoryBindings", repoBindings);
            return root;
        });
    }

    /**
     * Main method for direct JVM invocation (for debugging outside IntelliJ).
     * In production, the IntelliJ Platform launcher calls {@link #main(List)}.
     */
    public static void main(String[] args) {
        new PsiAnalyzerMain().main(List.of(args));
    }
}
