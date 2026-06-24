package io.yuan.octopus.analyzer;

import io.yuan.octopus.domain.*;
import io.yuan.octopus.entity.JavaProjectEntity;
import io.yuan.octopus.entity.RepoSnapshot;
import io.yuan.octopus.repository.JavaProjectRepository;
import io.yuan.octopus.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JVM platform adapter — analyses Java/Kotlin projects built with Maven or Gradle.
 *
 * <p>Delegates to:
 * <ul>
 *   <li>{@link ProjectScanService} — discovers Maven/Gradle sub-modules</li>
 *   <li>{@link CodeParseService} — JavaParser-based AST extraction</li>
 *   <li>{@link PSIExecutor} — IntelliJ PSI headless analysis (JVM-specific)</li>
 *   <li>{@link CallGraphBuildService} — builds directed call graphs</li>
 *   <li>{@link GraphStoreService} — persists graphs to Neo4j</li>
 *   <li>{@link MetadataStoreService} — persists class/method metadata to MySQL</li>
 * </ul>
 */
@Service
public class JvmProjectAnalyzer implements ProjectAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JvmProjectAnalyzer.class);

    private final ProjectScanService projectScanService;
    private final CodeParseService codeParseService;
    private final PSIExecutor psiExecutor;
    private final CallGraphBuildService callGraphBuildService;
    private final GraphStoreService graphStoreService;
    private final MetadataStoreService metadataStoreService;
    private final BackendProjectFlagService backendProjectFlagService;
    private final JavaProjectRepository javaProjectRepository;

    public JvmProjectAnalyzer(ProjectScanService projectScanService,
                              CodeParseService codeParseService,
                              PSIExecutor psiExecutor,
                              CallGraphBuildService callGraphBuildService,
                              GraphStoreService graphStoreService,
                              MetadataStoreService metadataStoreService,
                              BackendProjectFlagService backendProjectFlagService,
                              JavaProjectRepository javaProjectRepository) {
        this.projectScanService = projectScanService;
        this.codeParseService = codeParseService;
        this.psiExecutor = psiExecutor;
        this.callGraphBuildService = callGraphBuildService;
        this.graphStoreService = graphStoreService;
        this.metadataStoreService = metadataStoreService;
        this.backendProjectFlagService = backendProjectFlagService;
        this.javaProjectRepository = javaProjectRepository;
    }

    @Override
    public String getLanguage() {
        return "JVM";
    }

    @Override
    public boolean supports(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return false;
        // Check root-level build files
        if (hasJvmBuildFile(projectRoot)) return true;
        // Check immediate children (multi-module projects)
        try (var stream = Files.list(projectRoot)) {
            return stream.filter(Files::isDirectory)
                    .anyMatch(this::hasJvmBuildFile);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int getPriority() {
        return 10;
    }

    // ── Analysis pipeline ────────────────────────────────────────────────

    /**
     * Scans the workspace root and returns discovered JVM sub-projects.
     */
    public List<JavaProject> scanProjects(RepoSnapshot snapshot) {
        return projectScanService.scanProjects(snapshot);
    }

    /**
     * Scans a single local directory as a JVM project.
     */
    public JavaProject scanSingleProject(Path projectDir, String projectName) {
        return projectScanService.scanSingleProject(projectDir, projectName);
    }

    /**
     * Runs the full deep analysis pipeline for a list of JVM projects:
     * <ol>
     *   <li>Parse all modules with JavaParser (cross-module type resolution)</li>
     *   <li>Run global PSI analysis for the workspace</li>
     *   <li>Build per-module call graphs (PSI-augmented, JavaParser fallback)</li>
     *   <li>Store nodes → metadata → edges</li>
     * </ol>
     *
     * @return list of module names where PSI was degraded (fell back to JavaParser)
     */
    public List<String> analyzeDeep(AnalysisContext ctx,
                                    List<JavaProject> projects,
                                    RepoSnapshot snapshot) {
        List<String> psiDegraded = new ArrayList<>();

        // Collect all source directories for cross-module symbol resolution
        List<Path> allSourceDirs = projects.stream()
                .flatMap(p -> p.sourceDirectories().stream())
                .toList();

        // ── Phase 0: parse all modules ──────────────────────────────────
        record ProjectContext(JavaProject project, JavaProjectEntity entity,
                              ParseResult parseResult) {}
        List<ProjectContext> contexts = new ArrayList<>();

        for (JavaProject project : projects) {
            JavaProjectEntity entity = findOrCreateEntity(project, ctx);
            ParseResult parseResult = codeParseService.parseProject(project, allSourceDirs);
            contexts.add(new ProjectContext(project, entity, parseResult));
        }

        if (contexts.isEmpty()) return psiDegraded;

        // Global type registry for cross-module callee resolution
        Map<String, ParsedClass> globalTypeRegistry = buildGlobalTypeRegistry(
                contexts.stream().map(ProjectContext::parseResult).toList());

        // ── Global PSI analysis ─────────────────────────────────────────
        Path workspaceRoot = ctx.projectRoot().toAbsolutePath().normalize();
        JavaProject rootProject = new JavaProject(
                workspaceRoot.getFileName().toString(),
                workspaceRoot, null, allSourceDirs, null);
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
        List<Long> allProjectIds = contexts.stream().map(c -> c.entity().getId()).toList();

        // ── Phase 1: build call graphs, store nodes + metadata ──────────
        record GraphEntry(JavaProjectEntity entity, CallGraph callGraph) {}
        List<GraphEntry> graphEntries = new ArrayList<>();

        for (ProjectContext pctx : contexts) {
            CallGraph callGraph = null;

            if (globalPsiOutput != null) {
                callGraph = callGraphBuildService.buildCallGraphFromPSI(
                        globalPsiOutput, pctx.parseResult(), globalClassNames, globalTypeRegistry);
                callGraph = filterCallGraphForModule(callGraph, pctx.project().sourceDirectories());
                log.info("Built PSI-augmented call graph for project {}: {} nodes, {} edges",
                        pctx.project().projectName(), callGraph.nodes().size(), callGraph.edges().size());
            }

            if (callGraph == null || callGraph.nodes().isEmpty()) {
                CallGraph jpGraph = callGraphBuildService.buildCallGraph(pctx.parseResult(), globalTypeRegistry);
                log.info("Built JavaParser call graph for project {}: {} nodes, {} edges",
                        pctx.project().projectName(), jpGraph.nodes().size(), jpGraph.edges().size());

                if (!jpGraph.nodes().isEmpty()) {
                    psiDegraded.add(pctx.project().projectName());
                    callGraph = jpGraph;
                } else {
                    log.info("No code found in module {} (PSI + JavaParser both empty), skipping",
                            pctx.project().projectName());
                    continue;
                }
            }

            graphStoreService.clearProjectGraph(pctx.entity().getId(), ctx.branch(), ctx.commitId());
            graphStoreService.storeNodes(pctx.entity().getId(), ctx.branch(), ctx.commitId(), callGraph.nodes());
            metadataStoreService.storeMetadata(pctx.entity().getId(), ctx.branch(), pctx.parseResult());
            backendProjectFlagService.updateBackendProjectFlag(pctx.entity());

            graphEntries.add(new GraphEntry(pctx.entity(), callGraph));
        }

        // ── Phase 2: store edges after all modules' nodes exist ─────────
        for (GraphEntry entry : graphEntries) {
            graphStoreService.storeEdges(entry.entity().getId(), ctx.branch(), ctx.commitId(),
                    allProjectIds, entry.callGraph().edges());
        }

        // Mark projects as analysed
        markProjectsAnalyzed(projects, ctx);

        return psiDegraded;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean hasJvmBuildFile(Path dir) {
        return Files.exists(dir.resolve("pom.xml"))
                || Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("build.gradle.kts"));
    }

    private Map<String, ParsedClass> buildGlobalTypeRegistry(Collection<ParseResult> parseResults) {
        Map<String, ParsedClass> global = new HashMap<>();
        for (ParseResult pr : parseResults) {
            global.putAll(callGraphBuildService.buildTypeRegistry(pr));
        }
        return global;
    }

    /**
     * Filters a call graph to methods belonging to the given module's source dirs.
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

        Set<CallEdge> moduleEdges = fullGraph.edges().stream()
                .filter(e -> moduleNodeSignatures.contains(e.caller().signature()))
                .collect(Collectors.toSet());

        for (CallEdge edge : moduleEdges) {
            if (!moduleNodeSignatures.contains(edge.callee().signature())) {
                moduleNodes.add(edge.callee());
            }
        }

        return new CallGraph(moduleNodes, moduleEdges);
    }

    private JavaProjectEntity findOrCreateEntity(JavaProject project, AnalysisContext ctx) {
        if (ctx.repoConfigId() == null) {
            return findOrCreateLocalEntity(project, ctx);
        }
        return javaProjectRepository
                .findByRepoConfigIdAndBranchAndProjectName(ctx.repoConfigId(), ctx.branch(), project.projectName())
                .orElseGet(() -> {
                    JavaProjectEntity entity = JavaProjectEntity.builder()
                            .repoConfigId(ctx.repoConfigId())
                            .branch(ctx.branch())
                            .projectName(project.projectName())
                            .projectPath(project.projectPath().toString())
                            .buildTool(project.buildTool())
                            .javaVersion(project.javaVersion())
                            .build();
                    return javaProjectRepository.save(entity);
                });
    }

    private JavaProjectEntity findOrCreateLocalEntity(JavaProject project, AnalysisContext ctx) {
        String canonicalPath = project.projectPath().toAbsolutePath().normalize().toString();
        return javaProjectRepository
                .findByRepoConfigIdIsNullAndProjectPath(canonicalPath)
                .map(entity -> {
                    entity.setBranch(ctx.branch());
                    entity.setCommitId(ctx.commitId());
                    return javaProjectRepository.save(entity);
                })
                .orElseGet(() -> {
                    JavaProjectEntity entity = JavaProjectEntity.builder()
                            .repoConfigId(null)
                            .branch(ctx.branch())
                            .projectName(project.projectName())
                            .projectPath(canonicalPath)
                            .buildTool(project.buildTool())
                            .javaVersion(project.javaVersion())
                            .commitId(ctx.commitId())
                            .build();
                    return javaProjectRepository.save(entity);
                });
    }

    private void markProjectsAnalyzed(List<JavaProject> projects, AnalysisContext ctx) {
        LocalDateTime now = LocalDateTime.now();
        for (JavaProject project : projects) {
            if (ctx.repoConfigId() == null) {
                String canonicalPath = project.projectPath().toAbsolutePath().normalize().toString();
                javaProjectRepository.findByRepoConfigIdIsNullAndProjectPath(canonicalPath)
                        .ifPresent(entity -> {
                            entity.setCommitId(ctx.commitId());
                            entity.setAnalyzedAt(now);
                            javaProjectRepository.save(entity);
                        });
            } else {
                javaProjectRepository
                        .findByRepoConfigIdAndBranchAndProjectName(ctx.repoConfigId(), ctx.branch(), project.projectName())
                        .ifPresent(entity -> {
                            entity.setCommitId(ctx.commitId());
                            entity.setAnalyzedAt(now);
                        });
            }
        }
    }
}
