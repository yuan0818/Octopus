package io.yuan.octopus.service;

import io.yuan.octopus.analyzer.AnalysisContext;
import io.yuan.octopus.analyzer.AnalysisDispatcher;
import io.yuan.octopus.analyzer.ProjectAnalyzer;
import io.yuan.octopus.domain.CallEdge;
import io.yuan.octopus.domain.CallGraph;
import io.yuan.octopus.domain.MethodNode;
import io.yuan.octopus.domain.DiffResult;
import io.yuan.octopus.domain.DiscoveredProject;
import io.yuan.octopus.domain.PSIAnalysisOutput;
import io.yuan.octopus.domain.ParseResult;
import io.yuan.octopus.domain.ParsedClass;
import io.yuan.octopus.dto.request.LocalAnalysisRequest;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.exception.ValidationException;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
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
    private final AnalyzedProjectRepository javaProjectRepository;
    private final PSIExecutor psiExecutor;
    private final LocalGitDetector localGitDetector;
    private final BackendProjectFlagService backendProjectFlagService;
    private final AnalysisDispatcher analysisDispatcher;
    private final LocalPathPolicy localPathPolicy;

    /**
     * Background executor for analysis pipelines (virtual threads keep per-task cost low).
     * <p>
     * 并发由 {@link #analysisPermits} 限制：PSI 服务单实例单飞，无上限提交只会让任务在 PSI 端
     * 排队直到超时，并同时占用 Git 检出、解析、Neo4j 写入资源。超出上限的任务在虚拟线程上
     * 等待许可（阻塞成本极低），按提交顺序公平执行。
     */
    private final ExecutorService analysisExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore analysisPermits;

    public AnalysisOrchestrationService(RepoConfigService repoConfigService,
                                        GitService gitService,
                                        ProjectScanService projectScanService,
                                        CodeParseService codeParseService,
                                        CallGraphBuildService callGraphBuildService,
                                        GraphStoreService graphStoreService,
                                        MetadataStoreService metadataStoreService,
                                        AnalysisTaskService analysisTaskService,
                                        AnalyzedProjectRepository javaProjectRepository,
                                        BackendProjectFlagService backendProjectFlagService,
                                        PSIExecutor psiExecutor,
                                        LocalGitDetector localGitDetector,
                                        AnalysisDispatcher analysisDispatcher,
                                        LocalPathPolicy localPathPolicy,
                                        @Value("${octopus.analysis.max-concurrent:2}") int maxConcurrentAnalyses) {
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
        this.analysisDispatcher = analysisDispatcher;
        this.localPathPolicy = localPathPolicy;
        this.analysisPermits = new Semaphore(Math.max(1, maxConcurrentAnalyses), true);
        log.info("Analysis concurrency limit: {}", Math.max(1, maxConcurrentAnalyses));
    }

    /**
     * 提交后台分析任务；任务在获得并发许可后才真正执行。
     */
    private void submitBounded(Long taskId, Runnable pipeline) {
        analysisExecutor.submit(() -> {
            boolean acquired = false;
            try {
                if (!analysisPermits.tryAcquire()) {
                    log.info("Analysis task {} queued: all analysis slots are busy, waiting", taskId);
                    analysisPermits.acquire();
                }
                acquired = true;
                pipeline.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Analysis task {} interrupted while waiting for a slot", taskId);
                safeUpdateTaskFailed(taskId, "Interrupted before start (service shutting down)");
            } finally {
                if (acquired) {
                    analysisPermits.release();
                }
            }
        });
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
        submitBounded(task.getId(), () -> {
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
        // 规范化 + 白名单校验（M4）：解析符号链接后必须位于 allowed-roots 之下
        Path localPath = localPathPolicy.resolve(request.projectPath());

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
        submitBounded(finalTask.getId(), () -> runLocalPipeline(finalTask, localPath, projectName, branch, detectedCommitId));

        return analysisTaskService.getTask(task.getId());
    }

    /**
     * Runs the local analysis pipeline via the {@link AnalysisDispatcher}.
     * Detects the project language, routes to the matching adapter, and
     * updates task status at each step so the frontend can poll for progress.
     */
    private void runLocalPipeline(AnalysisTask task, Path localPath, String projectName,
                                  String branch, String detectedCommitId) {
        MDC.put("taskId", String.valueOf(task.getId()));
        try {
            List<AnalysisTaskStep> steps = analysisTaskService.getSteps(task.getId());
            Path resolvedPath = localPath.toAbsolutePath().normalize();
            RepoSnapshot snapshot = new RepoSnapshot(resolvedPath,
                    "local://" + resolvedPath, detectedCommitId, null);

            // Detect language via dispatcher; fall back to JVM for directories
            // without any recognized build/module files (preserves legacy behavior)
            ProjectAnalyzer analyzer = analysisDispatcher.detectAnalyzer(resolvedPath)
                    .orElseGet(analysisDispatcher::getJvmAnalyzer);
            AnalysisContext analysisCtx = new AnalysisContext(
                    resolvedPath, branch, detectedCommitId, null, task.getId());

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
                            List<DiscoveredProject> projects = analyzer.scanProjects(snapshot);
                            if (projects.isEmpty()) {
                                projects = List.of(analyzer.scanSingleProject(resolvedPath, projectName));
                            }
                            stepDegraded = analyzer.analyzeDeep(analysisCtx, projects, snapshot);
                        }
                        case "PARSE_DEEP" -> {
                            // SCAN step already ran the full analysis; skip to avoid double-processing
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
        submitBounded(taskId, () -> {
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

    /**
     * Starts a full analysis of an already-merged local working tree for a Gerrit
     * Change patchset (REQ-CTR-004, ADR-006). The merge tree and merge commit SHA
     * are produced upstream by {@link GitService#mergePatchsetOntoBase}; this method
     * skips the tag checkout (SYNC) and runs SCAN → PARSE_DEEP synchronously with
     * {@code commitId = mergeCommitId}, relying on {@link MetadataStoreService} and
     * {@link GraphStoreService} to flip {@code is_head} / {@code current} for the
     * new version.
     *
     * <p>Runs <b>synchronously</b> on the caller's thread (the change-review consumer
     * orchestrator), unlike {@link #startAnalysis} which returns immediately. On
     * failure the underlying analysis_task is marked FAILED and the exception is
     * rethrown so the orchestrator can mark the {@code change_review_task} as
     * {@code FAILED(ANALYSIS_FAILED)} (the analysis_task row is transient step
     * tracking; {@code change_review_task.currentPhase} is the durable truth — ADR-004).
     *
     * @param repoConfigId   repository configuration id
     * @param branch         target branch of the Change
     * @param mergeCommitId  merge commit SHA produced by the simulated merge (analysis commitId)
     * @param localPath      local working tree already left on the merge commit
     * @return the completed (or FAILED) analysis task
     */
    public AnalysisTask startChangeAnalysis(Long repoConfigId, String branch,
                                            String mergeCommitId, Path localPath) {
        RepoConfig repoConfig = repoConfigService.getById(repoConfigId);
        // SCAN + PARSE_DEEP steps only (no SYNC — merge tree already prepared).
        AnalysisTask task = analysisTaskService.createLocalTask(repoConfigId, null, branch);
        analysisTaskService.updateCommitId(task.getId(), mergeCommitId);
        task.setCommitId(mergeCommitId);

        String projectName = localPath.getFileName() != null
                ? localPath.getFileName().toString() : GitService.extractRepoName(repoConfig.getRepoUrl());

        runChangeAnalysisPipeline(task, localPath, projectName, branch, mergeCommitId);
        return analysisTaskService.getTask(task.getId());
    }

    /**
     * Synchronous SCAN → PARSE_DEEP pipeline for a change-triggered merge tree.
     * Mirrors {@link #runLocalPipeline} but runs on the caller's thread and
     * rethrows failures so the change-review orchestrator can fail the task.
     */
    private void runChangeAnalysisPipeline(AnalysisTask task, Path localPath, String projectName,
                                           String branch, String mergeCommitId) {
        MDC.put("taskId", String.valueOf(task.getId()));
        try {
            List<AnalysisTaskStep> steps = analysisTaskService.getSteps(task.getId());
            Path resolvedPath = localPath.toAbsolutePath().normalize();
            RepoSnapshot snapshot = new RepoSnapshot(resolvedPath,
                    "change-merge://" + resolvedPath, mergeCommitId, null);

            ProjectAnalyzer analyzer = analysisDispatcher.detectAnalyzer(resolvedPath)
                    .orElseGet(analysisDispatcher::getJvmAnalyzer);
            AnalysisContext analysisCtx = new AnalysisContext(
                    resolvedPath, branch, mergeCommitId, task.getRepoConfigId(), task.getId());

            List<DiscoveredProject> projects = null;
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
                            projects = analyzer.scanProjects(snapshot);
                            if (projects.isEmpty()) {
                                projects = List.of(analyzer.scanSingleProject(resolvedPath, projectName));
                            }
                            stepDegraded = analyzer.analyzeDeep(analysisCtx, projects, snapshot);
                        }
                        case "PARSE_DEEP" -> log.debug("PARSE_DEEP step skipped for change analysis (already processed during SCAN)");
                    }

                    String stepStatus = stepDegraded.isEmpty() ? "COMPLETED" : "DEGRADED";
                    String stepWarning = stepDegraded.isEmpty() ? null
                            : "PSI unavailable (JavaParser fallback) for: " + String.join(", ", stepDegraded);
                    analysisTaskService.updateStepStatus(step.getId(), stepStatus, stepWarning);

                } catch (Exception e) {
                    log.error("Step {} failed for change analysis task {}: {}", step.getStepName(), task.getId(), e.getMessage(), e);
                    analysisTaskService.updateStepStatus(step.getId(), "FAILED", e.getMessage());
                    analysisTaskService.updateTaskStatus(task.getId(), "FAILED", e.getMessage());
                    throw new AnalysisFailedException("Change analysis failed at step "
                            + step.getStepName() + ": " + e.getMessage(), e);
                }
            }

            // Keep java_project.commit_id consistent with the merge commit (is_head=true),
            // so PathQueryService.resolveEffectiveCommit's HEAD fallback is not misled.
            // analyzedTag stays null (change-review is not tag-driven).
            if (projects != null) {
                markProjectsAnalyzed(projects, task.getRepoConfigId(), branch, task);
            }
            analysisTaskService.updateTaskStatus(task.getId(), "COMPLETED", null);
        } catch (AnalysisFailedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unhandled exception in change analysis task {}: {}", task.getId(), e.getMessage(), e);
            safeUpdateTaskFailed(task.getId(), "Internal error: " + e.getMessage());
            throw new AnalysisFailedException("Change analysis failed: " + e.getMessage(), e);
        } finally {
            MDC.remove("taskId");
        }
    }

    /** Signals that a change-triggered analysis failed (REQ-CTR-004 → ANALYSIS_FAILED). */
    public static class AnalysisFailedException extends RuntimeException {
        public AnalysisFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 按检出目录的构建文件选择语言适配器；未识别目录回退 JVM（保持既有行为）。 */
    private ProjectAnalyzer analyzerOf(Path projectRoot) {
        return analysisDispatcher.detectAnalyzer(projectRoot)
                .orElseGet(analysisDispatcher::getJvmAnalyzer);
    }

    // -------------------------------------------------------------------------
    // Pipeline execution
    // -------------------------------------------------------------------------

    private void executeFromStep(AnalysisTask task, List<AnalysisTaskStep> steps,
                                 RepoConfig repoConfig, int startIndex) {
        RepoSnapshot snapshot = null;
        Optional<DiffResult> diffResult = Optional.empty();
        List<DiscoveredProject> projects = null;
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
                        projects = analyzerOf(snapshot.repoLocalPath()).scanProjects(snapshot);
                    }
                    case "PARSE_DEEP" -> {
                        if (snapshot == null) snapshot = gitService.syncAndCheckout(repoConfig, task.getTag());
                        ProjectAnalyzer stepAnalyzer = analyzerOf(snapshot.repoLocalPath());
                        if (projects == null) projects = stepAnalyzer.scanProjects(snapshot);
                        if ("JVM".equals(stepAnalyzer.getLanguage())) {
                            stepDegraded = processProjectsDeep(task, projects, repoConfig.getId(), branch, snapshot,
                                    branchBaselineTag, diffResult);
                        } else {
                            // 非 JVM（如 Go）：走 ProjectAnalyzer.analyzeDeep 的语言无关管线，
                            // 语义与本地分析一致（清图→存节点→存元数据→存边→标记已分析）
                            AnalysisContext deepCtx = new AnalysisContext(snapshot.repoLocalPath(), branch,
                                    task.getCommitId(), repoConfig.getId(), task.getId());
                            stepDegraded = stepAnalyzer.analyzeDeep(deepCtx, projects, snapshot);
                        }
                    }
                    case "PARSE", "BUILD", "STORE_GRAPH", "STORE_METADATA" -> {
                        if (snapshot == null) snapshot = gitService.syncAndCheckout(repoConfig, task.getTag());
                        if (projects == null) projects = analyzerOf(snapshot.repoLocalPath()).scanProjects(snapshot);
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
                                     List<DiscoveredProject> projects,
                                     Long repoConfigId,
                                     String branch, RepoSnapshot snapshot,
                                     String branchBaselineTag, Optional<DiffResult> branchDiffResult) {
        List<String> psiDegraded = new ArrayList<>();

        // Collect all source directories for cross-module symbol resolution
        List<Path> allSourceDirs = projects.stream()
                .flatMap(p -> p.sourceDirectories().stream())
                .toList();

        // --- Phase 0: parse all projects and compute per-project diffs ---
        record ProjectContext(DiscoveredProject project, AnalyzedProjectEntity entity,
                              PSIAnalysisOutput psiOutput, ParseResult parseResult,
                              Optional<DiffResult> projectDiff) {}
        List<ProjectContext> contexts = new ArrayList<>();

        for (DiscoveredProject project : projects) {
            AnalyzedProjectEntity projectEntity = findOrCreateProjectEntity(project, repoConfigId, branch);
            projectEntity.setRepositoryRootPath(RepositoryIdentity.root(snapshot.repoLocalPath().toString()));
            javaProjectRepository.save(projectEntity);
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
        record GraphEntry(AnalyzedProjectEntity entity, CallGraph callGraph,
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

            metadataStoreService.storeMetadata(ctx.entity().getId(), branch, task.getCommitId(), ctx.parseResult());

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
                                 List<DiscoveredProject> projects,
                                 Long repoConfigId,
                                 String branch, RepoSnapshot snapshot,
                                 String branchBaselineTag, Optional<DiffResult> branchDiffResult) {
        for (DiscoveredProject project : projects) {
            AnalyzedProjectEntity projectEntity = findOrCreateProjectEntity(project, repoConfigId, branch);
            projectEntity.setRepositoryRootPath(RepositoryIdentity.root(snapshot.repoLocalPath().toString()));
            javaProjectRepository.save(projectEntity);
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
                        metadataStoreService.updateMetadataDiff(projectEntity.getId(), branch, task.getCommitId(), parseResult, dr);
                    } else {
                        ParseResult parseResult = codeParseService.parseProject(project);
                        metadataStoreService.storeMetadata(projectEntity.getId(), branch, task.getCommitId(), parseResult);
                    }
                    backendProjectFlagService.updateBackendProjectFlag(projectEntity);
                }
            }
        }
    }

    private Optional<DiffResult> resolveProjectDiff(AnalyzedProjectEntity projectEntity,
                                                    DiscoveredProject project,
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
    private DiffResult filterForProject(DiffResult diff, DiscoveredProject project) {
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

    private void copyCurrentGraphForUnchangedCommit(List<DiscoveredProject> projects, Long repoConfigId,
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
                .map(AnalyzedProjectEntity::getAnalyzedTag)
                .orElse(null);
    }

    private AnalyzedProjectEntity findOrCreateProjectEntity(DiscoveredProject project, Long repoConfigId,
                                                        String branch) {
        return javaProjectRepository
                .findByRepoConfigIdAndBranchAndProjectName(repoConfigId, branch, project.projectName())
                .orElseGet(() -> {
                    AnalyzedProjectEntity entity = AnalyzedProjectEntity.builder()
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

    private void markProjectsAnalyzed(List<DiscoveredProject> projects, Long repoConfigId,
                                      String branch, AnalysisTask task) {
        LocalDateTime now = LocalDateTime.now();
        for (DiscoveredProject project : projects) {
            if (repoConfigId == null) {
                // Local project: look up by canonical path + branch (per-branch rows)
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
                            // change 触发的分析 task.tag 为 null：不能清空 tag 基线，
                            // 否则下次 tag 增量分析退化（getPreviousTag 拿不到上一版本）
                            if (task.getTag() != null) {
                                entity.setAnalyzedTag(task.getTag());
                            }
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
