package io.yuan.octopus.service;

import io.yuan.octopus.domain.CallEdge;
import io.yuan.octopus.domain.CallGraph;
import io.yuan.octopus.domain.MethodNode;
import io.yuan.octopus.domain.DiffResult;
import io.yuan.octopus.domain.JavaProject;
import io.yuan.octopus.domain.PSIAnalysisOutput;
import io.yuan.octopus.domain.ParseResult;
import io.yuan.octopus.domain.ParsedClass;
import io.yuan.octopus.dto.request.LocalAnalysisRequest;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.exception.ValidationException;
import io.yuan.octopus.repository.JavaProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class AnalysisOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrationService.class);

    private final RepoConfigService repoConfigService;
    private final GitService gitService;
    private final ProjectScanService projectScanService;
    private final CodeParseService codeParseService;
    private final CallGraphBuildService callGraphBuildService;
    private final GraphStoreService graphStoreService;
    private final MetadataStoreService metadataStoreService;
    private final AnalysisTaskService analysisTaskService;
    private final JavaProjectRepository javaProjectRepository;
    private final PSIExecutor psiExecutor;
    private final LocalGitDetector localGitDetector;
    private final BackendProjectFlagService backendProjectFlagService;

    /**
     * Single-thread executor for background analysis pipeline.
     * Using virtual threads (Java 21) to keep per-task cost low.
     */
    private final ExecutorService analysisExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AnalysisOrchestrationService(RepoConfigService repoConfigService,
                                        GitService gitService,
                                        ProjectScanService projectScanService,
                                        CodeParseService codeParseService,
                                        CallGraphBuildService callGraphBuildService,
                                        GraphStoreService graphStoreService,
                                        MetadataStoreService metadataStoreService,
                                        AnalysisTaskService analysisTaskService,
                                        JavaProjectRepository javaProjectRepository,
                                        BackendProjectFlagService backendProjectFlagService,
                                        PSIExecutor psiExecutor,
                                        LocalGitDetector localGitDetector) {
        this.repoConfigService = repoConfigService;
        this.gitService = gitService;
        this.projectScanService = projectScanService;
        this.codeParseService = codeParseService;
        this.callGraphBuildService = callGraphBuildService;
        this.graphStoreService = graphStoreService;
        this.metadataStoreService = metadataStoreService;
        this.analysisTaskService = analysisTaskService;
        this.javaProjectRepository = javaProjectRepository;
        this.backendProjectFlagService = backendProjectFlagService;
        this.psiExecutor = psiExecutor;
        this.localGitDetector = localGitDetector;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts a full or incremental analysis for a specific Git tag.
     *
     * <p>If the repository was previously analysed at a different tag, only the Java
     * files that changed between the two tags are re-parsed and re-stored, making
     * subsequent analyses much faster.
     *
     * @param repoConfigId repository configuration id
     * @param tag          the Git tag to analyse (e.g. {@code v1.2.0})
     */
    public AnalysisTask startAnalysis(Long repoConfigId, String tag) {
        RepoConfig repoConfig = repoConfigService.getById(repoConfigId);
        AnalysisTask task = analysisTaskService.createTask(repoConfigId, tag, repoConfig.getBranch());
        List<AnalysisTaskStep> steps = analysisTaskService.getSteps(task.getId());

        // Submit pipeline to background thread so the HTTP response returns immediately
        analysisExecutor.submit(() -> {
            MDC.put("taskId", String.valueOf(task.getId()));
            try {
                executeFromStep(task, steps, repoConfig, 0);
            } catch (Exception e) {
                log.error("Unhandled exception in async analysis task {}: {}", task.getId(), e.getMessage(), e);
                safeUpdateTaskFailed(task.getId(), "Internal error: " + e.getMessage());
            } finally {
                MDC.remove("taskId");
            }
        });

        return analysisTaskService.getTask(task.getId());
    }

    /**
     * Starts a full directed-graph analysis on a local directory (no Git repository required).
     * The analysis pipeline: SCAN → PARSE_DEEP (producing a Neo4j call graph).
     *
     * @param request contains the absolute project path and optional project name
     */
    public AnalysisTask startLocalAnalysis(LocalAnalysisRequest request) {
        Path localPath = Path.of(request.projectPath());
        if (!Files.isDirectory(localPath)) {
            throw new IllegalArgumentException("Path is not a valid directory: " + request.projectPath());
        }

        String projectName = (request.projectName() != null && !request.projectName().isBlank())
                ? request.projectName() : localPath.getFileName().toString();

        // Auto-detect git branch and HEAD commit from the local path
        LocalGitDetector.GitInfo gitInfo = localGitDetector.detect(localPath).orElse(null);
        String branch = gitInfo != null ? gitInfo.branch() : "local";
        String detectedCommitId = gitInfo != null ? gitInfo.commitId() : null;

        if (gitInfo != null) {
            log.info("Detected git info for local project '{}': branch={}, commit={}",
                    projectName, gitInfo.branch(), gitInfo.commitId());
        } else {
            log.info("No git repository detected for local project '{}', using branch=local", projectName);
        }

        AnalysisTask task = analysisTaskService.createLocalTask(null, projectName, branch);
        if (detectedCommitId != null) {
            analysisTaskService.updateCommitId(task.getId(), detectedCommitId);
            task.setCommitId(detectedCommitId);
        }

        // Submit pipeline to background thread so the HTTP response returns immediately
        AnalysisTask finalTask = task;
        analysisExecutor.submit(() -> runLocalPipeline(finalTask, localPath, projectName, branch, detectedCommitId));

        return analysisTaskService.getTask(task.getId());
    }

    /**
     * Runs the local analysis pipeline (SCAN → PARSE_DEEP) asynchronously.
     * Updates task status in the database at each step so the frontend can poll for progress.
     */
    private void runLocalPipeline(AnalysisTask task, Path localPath, String projectName,
                                  String branch, String detectedCommitId) {
        MDC.put("taskId", String.valueOf(task.getId()));
        try {
            List<AnalysisTaskStep> steps = analysisTaskService.getSteps(task.getId());
            Path resolvedPath = localPath.toAbsolutePath().normalize();
            RepoSnapshot snapshot = new RepoSnapshot(resolvedPath,
                    "local://" + resolvedPath, detectedCommitId, null);

            for (AnalysisTaskStep step : steps) {
                if ("COMPLETED".equals(step.getStatus()) || "SKIPPED".equals(step.getStatus())
                        || "DEGRADED".equals(step.getStatus())) {
                    continue;
                }

                analysisTaskService.updateStepStatus(step.getId(), "RUNNING", null);

                try {
                    List<String> stepDegraded = List.of();
                    switch (step.getStepName()) {
                        case "SCAN" -> {
                            List<JavaProject> projects = projectScanService.scanProjects(snapshot);
                            if (projects.isEmpty()) {
                                projects = List.of(projectScanService.scanSingleProject(resolvedPath, projectName));
                            }
                            stepDegraded = processLocalDeep(task, projects, branch, snapshot);
                        }
                        case "PARSE_DEEP" -> {
                            // SCAN step already ran processLocalDeep; skip to avoid double-analysis
                            // (which would overwrite Neo4j data with a potentially partial second run)
                            log.debug("PARSE_DEEP step skipped for local analysis (already processed during SCAN)");
                        }
                    }

                    String stepStatus = stepDegraded.isEmpty() ? "COMPLETED" : "DEGRADED";
                    String stepWarning = stepDegraded.isEmpty() ? null
                            : "PSI unavailable (JavaParser fallback) for: " + String.join(", ", stepDegraded);
                    analysisTaskService.updateStepStatus(step.getId(), stepStatus, stepWarning);

                } catch (Exception e) {
                    log.error("Step {} failed for task {}: {}", step.getStepName(), task.getId(), e.getMessage(), e);
                    analysisTaskService.updateStepStatus(step.getId(), "FAILED", e.getMessage());
                    analysisTaskService.updateTaskStatus(task.getId(), "FAILED", e.getMessage());
                    return;
                }
            }

            analysisTaskService.updateTaskStatus(task.getId(), "COMPLETED", null);
        } catch (Exception e) {
            log.error("Unhandled exception in async analysis task {}: {}", task.getId(), e.getMessage(), e);
            safeUpdateTaskFailed(task.getId(), "Internal error: " + e.getMessage());
        } finally {
            MDC.remove("taskId");
        }
    }

    /**
     * Safely marks a task as FAILED, swallowing any secondary write errors.
     */
    private void safeUpdateTaskFailed(Long taskId, String message) {
        try {
            analysisTaskService.updateTaskStatus(taskId, "FAILED", message);
        } catch (Exception ignored) {
            // Last resort — the original exception is more important than this update
        }
    }

    private List<String> processLocalDeep(AnalysisTask task,
                                  List<JavaProject> projects,
                                  String branch, RepoSnapshot snapshot) {
        List<String> psiDegraded = new ArrayList<>();

        // Collect all source directories across every sub-module for cross-module symbol resolution
        List<Path> allSourceDirs = projects.stream()
                .flatMap(p -> p.sourceDirectories().stream())
                .toList();

        // --- Phase 0: Parse all modules with the combined type solver ---
        // We must parse everything first so we can build the global type registry,
        // which lets JavaParser resolve cross-module method calls.
        record ProjectContext(JavaProject project, JavaProjectEntity entity,
                              ParseResult parseResult) {}
        List<ProjectContext> contexts = new ArrayList<>();

        for (JavaProject project : projects) {
            JavaProjectEntity projectEntity = findOrCreateLocalProjectEntity(project, branch, task.getCommitId());
            ParseResult parseResult = codeParseService.parseProject(project, allSourceDirs);
            contexts.add(new ProjectContext(project, projectEntity, parseResult));
        }

        // Build a global type registry from all modules' ParseResults so that cross-module
        // callee classes are visible to buildCallGraph when PSI is not available.
        Map<String, ParsedClass> globalTypeRegistry = buildGlobalTypeRegistry(
                contexts.stream().map(ProjectContext::parseResult).toList());

        // ── Global PSI analysis ──────────────────────────────────────────────
        // Call PSI ONCE with the workspace root so it opens the parent Maven/Gradle
        // project and sees ALL modules.  This enables cross-module call resolution
        // that per-module PSI calls cannot support.
        Path workspaceRoot = snapshot.repoLocalPath().toAbsolutePath().normalize();
        JavaProject rootProject = new JavaProject(
                workspaceRoot.getFileName().toString(),
                workspaceRoot,
                null,
                allSourceDirs,
                null);
        PSIAnalysisOutput globalPsiOutput = psiExecutor.execute(rootProject);
        if (globalPsiOutput != null) {
            log.info("Global PSI analysis for workspace {}: {} methods, {} edges, {} beans",
                    workspaceRoot,
                    globalPsiOutput.methods().size(),
                    globalPsiOutput.edges().size(),
                    globalPsiOutput.beans().size());
        } else {
            log.info("PSI not available for workspace {}; all modules will use JavaParser", workspaceRoot);
        }
        Set<String> globalClassNames = globalTypeRegistry.keySet();

        // Collect all project entity IDs so storeEdges can scope the callee MATCH
        List<Long> allProjectIds = contexts.stream()
                .map(c -> c.entity().getId())
                .toList();

        // Determine source-dir prefixes per module for graph filtering
        List<Path> workspaceSourceDirs = allSourceDirs;

        // --- Phase 1: Build call graphs and store nodes + metadata ---
        // Edges are NOT stored yet so that cross-module callee nodes exist before edge creation.
        record GraphEntry(JavaProjectEntity entity, CallGraph callGraph) {}
        List<GraphEntry> graphEntries = new ArrayList<>();

        for (ProjectContext ctx : contexts) {
            CallGraph callGraph = null;
            if (globalPsiOutput != null) {
                // Build a complete project-wide call graph, then filter to this module.
                // The global class nameset prevents cross-module callees from being
                // stripped by the third-party filter.
                callGraph = callGraphBuildService.buildCallGraphFromPSI(
                        globalPsiOutput, ctx.parseResult(), globalClassNames, globalTypeRegistry);

                // Filter the graph to only the methods belonging to THIS module
                // (where the source file lives under one of this project's source dirs).
                callGraph = filterCallGraphForModule(callGraph, ctx.project().sourceDirectories());

                log.info("Built PSI-augmented call graph for project {}: {} nodes, {} edges",
                        ctx.project().projectName(), callGraph.nodes().size(), callGraph.edges().size());
            }

            if (callGraph == null || callGraph.nodes().isEmpty()) {
                psiDegraded.add(ctx.project().projectName());
                callGraph = callGraphBuildService.buildCallGraph(ctx.parseResult(), globalTypeRegistry);
                log.info("Built JavaParser call graph for project {} (PSI not available): {} nodes, {} edges",
                        ctx.project().projectName(), callGraph.nodes().size(), callGraph.edges().size());
            }

            graphStoreService.clearProjectGraph(ctx.entity().getId(), branch, task.getCommitId());
            graphStoreService.storeNodes(ctx.entity().getId(), branch, task.getCommitId(), callGraph.nodes());

            metadataStoreService.storeMetadata(ctx.entity().getId(), branch, ctx.parseResult());
            backendProjectFlagService.updateBackendProjectFlag(ctx.entity());

            graphEntries.add(new GraphEntry(ctx.entity(), callGraph));
        }

        // --- Phase 2: Store edges for all modules ---
        // All callee nodes now exist in Neo4j, so cross-module MATCH succeeds.
        for (GraphEntry entry : graphEntries) {
            graphStoreService.storeEdges(entry.entity().getId(), branch, task.getCommitId(),
                    allProjectIds, entry.callGraph().edges());
        }

        markProjectsAnalyzed(projects, null, branch, task);
        return psiDegraded;
    }

    /**
     * Filters a call graph down to methods that belong to the given module.
     * A method belongs to a module when its source file path starts with one
     * of the module's source directory prefixes.
     * <p>
     * Edges whose CALLER belongs to this module are retained, even when the
     * callee lives in a different module.  Cross-module callee nodes are also
     * kept in the returned graph so they can be stored as part of this module's
     * node set, guaranteeing that the callee exists in Neo4j when the edge is
     * created during Phase 2.
     */
    private CallGraph filterCallGraphForModule(CallGraph fullGraph, List<Path> moduleSourceDirs) {
        Set<String> dirPrefixes = moduleSourceDirs.stream()
                .map(d -> d.toAbsolutePath().normalize().toString() + "/")
                .collect(Collectors.toSet());

        Set<MethodNode> moduleNodes = fullGraph.nodes().stream()
                .filter(n -> n.sourceFilePath() != null
                        && dirPrefixes.stream().anyMatch(prefix -> n.sourceFilePath().startsWith(prefix)))
                .collect(Collectors.toSet());

        Set<String> moduleNodeSignatures = moduleNodes.stream()
                .map(MethodNode::signature)
                .collect(Collectors.toSet());

        // Keep edges whose CALLER is in this module (even if callee is cross-module)
        Set<CallEdge> moduleEdges = fullGraph.edges().stream()
                .filter(e -> moduleNodeSignatures.contains(e.caller().signature()))
                .collect(Collectors.toSet());

        // Include cross-module callee nodes so they exist in Neo4j for the edge MATCH
        for (CallEdge edge : moduleEdges) {
            if (!moduleNodeSignatures.contains(edge.callee().signature())) {
                moduleNodes.add(edge.callee());
            }
        }

        return new CallGraph(moduleNodes, moduleEdges);
    }

    /**
     * Builds a combined type registry from all the given ParseResults.
     * Used to make cross-module callee classes visible when building a JavaParser call graph.
     */
    private Map<String, ParsedClass> buildGlobalTypeRegistry(Collection<ParseResult> parseResults) {
        Map<String, ParsedClass> global = new HashMap<>();
        for (ParseResult pr : parseResults) {
            global.putAll(callGraphBuildService.buildTypeRegistry(pr));
        }
        return global;
    }

    /**
     * Retries a previously failed analysis task from its first failed step.
     * The original tag is re-used from the task record.
     */
    public AnalysisTask retryAnalysis(Long taskId) {
        AnalysisTask task = analysisTaskService.getTask(taskId);
        List<AnalysisTaskStep> steps = analysisTaskService.getSteps(taskId);

        int startIndex = -1;
        for (int i = 0; i < steps.size(); i++) {
            if ("FAILED".equals(steps.get(i).getStatus())) {
                startIndex = i;
                break;
            }
        }

        if (startIndex < 0) {
            return task;
        }

        if (task.getRepoConfigId() == null) {
            throw new ValidationException("Local analysis tasks cannot be retried in place; start a new local analysis for the same path");
        }

        analysisTaskService.updateTaskStatus(taskId, "RUNNING", null);
        RepoConfig repoConfig = repoConfigService.getById(task.getRepoConfigId());
        int asyncStartIndex = startIndex; // effectively-final copy for the lambda

        // Submit retry to background thread
        analysisExecutor.submit(() -> {
            MDC.put("taskId", String.valueOf(taskId));
            try {
                executeFromStep(task, steps, repoConfig, asyncStartIndex);
            } catch (Exception e) {
                log.error("Unhandled exception in async retry task {}: {}", taskId, e.getMessage(), e);
                safeUpdateTaskFailed(taskId, "Internal error: " + e.getMessage());
            } finally {
                MDC.remove("taskId");
            }
        });

        return analysisTaskService.getTask(taskId);
    }

    public AnalysisTask getTaskStatus(Long taskId) {
        return analysisTaskService.getTask(taskId);
    }

    // -------------------------------------------------------------------------
    // Pipeline execution
    // -------------------------------------------------------------------------

    private void executeFromStep(AnalysisTask task, List<AnalysisTaskStep> steps,
                                 RepoConfig repoConfig, int startIndex) {
        RepoSnapshot snapshot = null;
        Optional<DiffResult> diffResult = Optional.empty();
        List<JavaProject> projects = null;
        String branch = repoConfig.getBranch();
        String branchBaselineTag = null;

        for (int i = startIndex; i < steps.size(); i++) {
            AnalysisTaskStep step = steps.get(i);

            if ("COMPLETED".equals(step.getStatus()) || "SKIPPED".equals(step.getStatus())
                    || "DEGRADED".equals(step.getStatus())) {
                continue;
            }

            if (shouldSkip(step.getStepName(), diffResult)) {
                String reason = "No Java file changes between tags "
                        + branchBaselineTag + " -> " + task.getTag()
                        + " (branch=" + branch + ")";
                try {
                    if ("PARSE_DEEP".equals(step.getStepName()) && projects != null) {
                        copyCurrentGraphForUnchangedCommit(projects, repoConfig.getId(), branch, task.getCommitId());
                    }
                    analysisTaskService.updateStepStatus(step.getId(), "SKIPPED", reason);
                } catch (Exception e) {
                    log.error("Step {} failed while reusing unchanged graph for task {}: {}",
                            step.getStepName(), task.getId(), e.getMessage(), e);
                    analysisTaskService.updateStepStatus(step.getId(), "FAILED", e.getMessage());
                    analysisTaskService.updateTaskStatus(task.getId(), "FAILED", e.getMessage());
                    return;
                }
                continue;
            }

            analysisTaskService.updateStepStatus(step.getId(), "RUNNING", null);

            try {
                List<String> stepDegraded = List.of();
                switch (step.getStepName()) {
                    case "SYNC" -> {
                        branchBaselineTag = getPreviousTag(repoConfig.getId(), branch);
                        snapshot = gitService.syncAndCheckout(repoConfig, task.getTag());
                        task.setCommitId(snapshot.commitId());
                        analysisTaskService.updateCommitId(task.getId(), snapshot.commitId());
                        analysisTaskService.updateTaskStatus(task.getId(), "RUNNING", null);

                        diffResult = gitService.computeDiff(
                                snapshot.repoLocalPath(), branchBaselineTag, task.getTag());

                        log.info("Analysis for branch {} tag {} (commitId {}): {}",
                                branch, task.getTag(), snapshot.commitId(),
                                diffResult.map(d -> d.isEmpty()
                                        ? "no Java changes - subsequent steps will be skipped"
                                        : d.changedFilePaths().size() + " changed, "
                                        + d.deletedFilePaths().size() + " deleted Java files")
                                        .orElse("no previous tag - performing full analysis"));
                    }
                    case "SCAN" -> {
                        if (snapshot == null) snapshot = gitService.syncAndCheckout(repoConfig, task.getTag());
                        projects = projectScanService.scanProjects(snapshot);
                    }
                    case "PARSE_DEEP" -> {
                        if (snapshot == null) snapshot = gitService.syncAndCheckout(repoConfig, task.getTag());
                        if (projects == null) projects = projectScanService.scanProjects(snapshot);
                        stepDegraded = processProjectsDeep(task, projects, repoConfig.getId(), branch, snapshot,
                                branchBaselineTag, diffResult);
                    }
                    case "PARSE", "BUILD", "STORE_GRAPH", "STORE_METADATA" -> {
                        if (snapshot == null) snapshot = gitService.syncAndCheckout(repoConfig, task.getTag());
                        if (projects == null) projects = projectScanService.scanProjects(snapshot);
                        processProjects(task, step.getStepName(), projects, repoConfig.getId(), branch, snapshot,
                                branchBaselineTag, diffResult);
                    }
                }

                String stepStatus = stepDegraded.isEmpty() ? "COMPLETED" : "DEGRADED";
                String stepWarning = stepDegraded.isEmpty() ? null
                        : "PSI unavailable (JavaParser fallback) for: " + String.join(", ", stepDegraded);
                analysisTaskService.updateStepStatus(step.getId(), stepStatus, stepWarning);

            } catch (Exception e) {
                log.error("Step {} failed for task {}: {}", step.getStepName(), task.getId(), e.getMessage(), e);
                analysisTaskService.updateStepStatus(step.getId(), "FAILED", e.getMessage());
                analysisTaskService.updateTaskStatus(task.getId(), "FAILED", e.getMessage());
                return;
            }
        }

        if (projects != null) {
            markProjectsAnalyzed(projects, repoConfig.getId(), branch, task);
        }
        analysisTaskService.updateTaskStatus(task.getId(), "COMPLETED", null);
    }

    /**
     * Returns {@code true} when the step should be skipped because a diff was computed
     * and it contains no Java file changes.
     */
    private boolean shouldSkip(String stepName, Optional<DiffResult> diffResult) {
        if (diffResult.isEmpty()) return false;                          // full analysis
        if (!diffResult.get().isEmpty()) return false;                   // there are changes
        // diff present + empty → no changes; skip all parsing/storing steps
        return Set.of("PARSE", "BUILD", "STORE_GRAPH", "STORE_METADATA", "PARSE_DEEP").contains(stepName);
    }

    // -------------------------------------------------------------------------
    // Deep analysis (PSI-backed) per-project processing
    // -------------------------------------------------------------------------

    private List<String> processProjectsDeep(AnalysisTask task,
                                     List<JavaProject> projects,
                                     Long repoConfigId,
                                     String branch, RepoSnapshot snapshot,
                                     String branchBaselineTag, Optional<DiffResult> branchDiffResult) {
        List<String> psiDegraded = new ArrayList<>();

        // Collect all source directories for cross-module symbol resolution
        List<Path> allSourceDirs = projects.stream()
                .flatMap(p -> p.sourceDirectories().stream())
                .toList();

        // --- Phase 0: parse all projects and compute per-project diffs ---
        record ProjectContext(JavaProject project, JavaProjectEntity entity,
                              PSIAnalysisOutput psiOutput, ParseResult parseResult,
                              Optional<DiffResult> projectDiff) {}
        List<ProjectContext> contexts = new ArrayList<>();

        for (JavaProject project : projects) {
            JavaProjectEntity projectEntity = findOrCreateProjectEntity(project, repoConfigId, branch);
            Optional<DiffResult> projectDiff = resolveProjectDiff(projectEntity, project, snapshot,
                    branchBaselineTag, branchDiffResult, task.getTag());

            PSIAnalysisOutput psiOutput = psiExecutor.execute(project);

            // PSI extraction is project-wide and graph snapshots are rebuilt atomically.
            // Keep JavaParser metadata project-wide as well; filtering PSI nodes against only
            // changed files would silently delete unchanged methods from the rebuilt graph.
            ParseResult parseResult = codeParseService.parseProject(project, allSourceDirs);

            contexts.add(new ProjectContext(project, projectEntity, psiOutput, parseResult, projectDiff));
        }

        if (contexts.isEmpty()) {
            return psiDegraded;
        }

        // Global type registry for cross-module callee resolution (JavaParser fallback path)
        Map<String, ParsedClass> globalTypeRegistry = buildGlobalTypeRegistry(
                contexts.stream().map(ProjectContext::parseResult).toList());

        List<Long> allProjectIds = contexts.stream()
                .map(c -> c.entity().getId())
                .toList();

        // --- Phase 1: build graphs, store nodes + metadata ---
        record GraphEntry(JavaProjectEntity entity, CallGraph callGraph,
                          ParseResult parseResult, Optional<DiffResult> projectDiff) {}
        List<GraphEntry> graphEntries = new ArrayList<>();

        for (ProjectContext ctx : contexts) {
            CallGraph callGraph;
            if (ctx.psiOutput() != null) {
                callGraph = callGraphBuildService.buildCallGraphFromPSI(ctx.psiOutput(), ctx.parseResult());
                log.info("Built PSI-augmented call graph for project {}: {} nodes, {} edges",
                        ctx.project().projectName(), callGraph.nodes().size(), callGraph.edges().size());
            } else {
                psiDegraded.add(ctx.project().projectName());
                callGraph = callGraphBuildService.buildCallGraph(ctx.parseResult(), globalTypeRegistry);
                log.info("Built JavaParser call graph for project {} (PSI not available): {} nodes, {} edges",
                        ctx.project().projectName(), callGraph.nodes().size(), callGraph.edges().size());
            }

            // Store all nodes first. Every module receives a snapshot for this commit, even
            // when its own files did not change, so cross-module edges can resolve consistently.
            graphStoreService.clearProjectGraph(ctx.entity().getId(), branch, task.getCommitId());
            graphStoreService.storeNodes(ctx.entity().getId(), branch, task.getCommitId(), callGraph.nodes());

            metadataStoreService.storeMetadata(ctx.entity().getId(), branch, ctx.parseResult());

            backendProjectFlagService.updateBackendProjectFlag(ctx.entity());
            graphEntries.add(new GraphEntry(ctx.entity(), callGraph, ctx.parseResult(), ctx.projectDiff()));
        }

        // --- Phase 2: store edges after every module's nodes exist ---
        for (GraphEntry entry : graphEntries) {
            graphStoreService.storeEdges(entry.entity().getId(), branch, task.getCommitId(),
                    allProjectIds, entry.callGraph().edges());
        }

        return psiDegraded;
    }

    // -------------------------------------------------------------------------
    // Per-project processing
    // -------------------------------------------------------------------------

    private void processProjects(AnalysisTask task, String stepName,
                                 List<JavaProject> projects,
                                 Long repoConfigId,
                                 String branch, RepoSnapshot snapshot,
                                 String branchBaselineTag, Optional<DiffResult> branchDiffResult) {
        for (JavaProject project : projects) {
            JavaProjectEntity projectEntity = findOrCreateProjectEntity(project, repoConfigId, branch);
            Optional<DiffResult> projectDiff = resolveProjectDiff(projectEntity, project, snapshot,
                    branchBaselineTag, branchDiffResult, task.getTag());

            if (projectDiff.isPresent() && projectDiff.get().isEmpty()) {
                log.debug("No Java changes in project {} branch {} for tag {} - skipping step {}",
                        project.projectName(), branch, task.getTag(), stepName);
                continue;
            }

            switch (stepName) {
                case "PARSE" -> codeParseService.parseProject(project);
                case "BUILD" -> {
                    ParseResult parseResult = codeParseService.parseProject(project);
                    callGraphBuildService.buildCallGraph(parseResult);
                }
                case "STORE_GRAPH" -> {
                    ParseResult parseResult = codeParseService.parseProject(project);
                    CallGraph callGraph = callGraphBuildService.buildCallGraph(parseResult);
                    graphStoreService.storeGraph(projectEntity.getId(), branch, task.getCommitId(), callGraph);
                }
                case "STORE_METADATA" -> {
                    if (projectDiff.isPresent()) {
                        DiffResult dr = projectDiff.get();
                        ParseResult parseResult = codeParseService.parseFiles(project, dr.changedFilePaths());
                        metadataStoreService.updateMetadataDiff(projectEntity.getId(), branch, parseResult, dr);
                    } else {
                        ParseResult parseResult = codeParseService.parseProject(project);
                        metadataStoreService.storeMetadata(projectEntity.getId(), branch, parseResult);
                    }
                    backendProjectFlagService.updateBackendProjectFlag(projectEntity);
                }
            }
        }
    }

    private Optional<DiffResult> resolveProjectDiff(JavaProjectEntity projectEntity,
                                                    JavaProject project,
                                                    RepoSnapshot snapshot,
                                                    String branchBaselineTag,
                                                    Optional<DiffResult> branchDiffResult,
                                                    String targetTag) {
        String projectBaselineTag = projectEntity.getAnalyzedTag();
        if (projectBaselineTag == null) {
            return Optional.empty();
        }

        // Fast path: reuse branch-level diff if this project shares the branch baseline tag.
        if (Objects.equals(projectBaselineTag, branchBaselineTag)) {
            return branchDiffResult.map(dr -> filterForProject(dr, project));
        }

        // Fallback: project baseline lags/diverges, compute a project-specific tag diff.
        return gitService.computeDiff(snapshot.repoLocalPath(), projectBaselineTag, targetTag)
                .map(dr -> filterForProject(dr, project));
    }

    /**
     * Filters a repository-level {@link DiffResult} down to files that reside under
     * any source directory of the given project.
     */
    private DiffResult filterForProject(DiffResult diff, JavaProject project) {
        Set<String> sourceDirs = project.sourceDirectories().stream()
                .map(p -> p.toString() + java.io.File.separator)
                .collect(Collectors.toSet());

        Set<String> changed = diff.changedFilePaths().stream()
                .filter(f -> sourceDirs.stream().anyMatch(f::startsWith))
                .collect(Collectors.toSet());

        Set<String> deleted = diff.deletedFilePaths().stream()
                .filter(f -> sourceDirs.stream().anyMatch(f::startsWith))
                .collect(Collectors.toSet());

        return new DiffResult(changed, deleted);
    }

    private void copyCurrentGraphForUnchangedCommit(List<JavaProject> projects, Long repoConfigId,
                                                    String branch, String commitId) {
        List<Long> projectIds = projects.stream()
                .map(project -> findOrCreateProjectEntity(project, repoConfigId, branch).getId())
                .toList();
        graphStoreService.copyCurrentSnapshots(projectIds, branch, commitId);
        log.info("Copied current graph snapshot for {} unchanged projects at commit {}", projectIds.size(), commitId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns the most recent analysed tag for this repo+branch, or {@code null}. */
    private String getPreviousTag(Long repoConfigId, String branch) {
        return javaProjectRepository
                .findFirstByRepoConfigIdAndBranchAndAnalyzedTagIsNotNullOrderByAnalyzedAtDescIdDesc(repoConfigId, branch)
                .map(JavaProjectEntity::getAnalyzedTag)
                .orElse(null);
    }

    private JavaProjectEntity findOrCreateProjectEntity(JavaProject project, Long repoConfigId,
                                                        String branch) {
        return javaProjectRepository
                .findByRepoConfigIdAndBranchAndProjectName(repoConfigId, branch, project.projectName())
                .orElseGet(() -> {
                    JavaProjectEntity entity = JavaProjectEntity.builder()
                            .repoConfigId(repoConfigId)
                            .branch(branch)
                            .projectName(project.projectName())
                            .projectPath(project.projectPath().toString())
                            .buildTool(project.buildTool())
                            .javaVersion(project.javaVersion())
                            .build();
                    return javaProjectRepository.save(entity);
                });
    }

    /**
     * Finds or creates a local project entity using its canonical path as the unique key,
     * preventing collisions between different local directories with the same project name.
     */
    private JavaProjectEntity findOrCreateLocalProjectEntity(JavaProject project, String branch,
                                                             String commitId) {
        String canonicalPath = project.projectPath().toAbsolutePath().normalize().toString();
        return javaProjectRepository
                .findByRepoConfigIdIsNullAndProjectPath(canonicalPath)
                .map(entity -> {
                    // Update branch/commitId in case the project was re-analysed on a different branch
                    entity.setBranch(branch);
                    entity.setCommitId(commitId);
                    return javaProjectRepository.save(entity);
                })
                .orElseGet(() -> {
                    JavaProjectEntity entity = JavaProjectEntity.builder()
                            .repoConfigId(null)
                            .branch(branch)
                            .projectName(project.projectName())
                            .projectPath(canonicalPath)
                            .buildTool(project.buildTool())
                            .javaVersion(project.javaVersion())
                            .commitId(commitId)
                            .build();
                    return javaProjectRepository.save(entity);
                });
    }

    private void markProjectsAnalyzed(List<JavaProject> projects, Long repoConfigId,
                                      String branch, AnalysisTask task) {
        LocalDateTime now = LocalDateTime.now();
        for (JavaProject project : projects) {
            if (repoConfigId == null) {
                // Local project: look up by canonical path
                String canonicalPath = project.projectPath().toAbsolutePath().normalize().toString();
                javaProjectRepository.findByRepoConfigIdIsNullAndProjectPath(canonicalPath)
                        .ifPresent(entity -> {
                            entity.setCommitId(task.getCommitId());
                            entity.setAnalyzedAt(now);
                            javaProjectRepository.save(entity);
                        });
            } else {
                javaProjectRepository
                        .findByRepoConfigIdAndBranchAndProjectName(repoConfigId, branch, project.projectName())
                        .ifPresent(entity -> {
                            entity.setCommitId(task.getCommitId());
                            entity.setAnalyzedTag(task.getTag());
                            entity.setAnalyzedAt(now);
                            javaProjectRepository.save(entity);
                        });
            }
        }
    }

    /**
     * Updates the {@code isBackendProject} flag on the given project entity by checking
     * whether any HTTP endpoint annotations exist in the stored class metadata.
     * Always writes either {@code true} or {@code false} to overwrite stale values.
     */
    @PreDestroy
    public void shutdownExecutor() {
        analysisExecutor.close();
    }
}
