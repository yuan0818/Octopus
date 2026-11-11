package io.yuan.octopus.analyzer;

import io.yuan.octopus.domain.CallEdge;
import io.yuan.octopus.domain.CallGraph;
import io.yuan.octopus.domain.DiscoveredProject;
import io.yuan.octopus.domain.GoAnalysisOutput;
import io.yuan.octopus.domain.MethodNode;
import io.yuan.octopus.domain.ParseResult;
import io.yuan.octopus.domain.ParsedClass;
import io.yuan.octopus.domain.ParsedFile;
import io.yuan.octopus.entity.AnalyzedProjectEntity;
import io.yuan.octopus.entity.RepoSnapshot;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import io.yuan.octopus.service.GoAnalyzerExecutor;
import io.yuan.octopus.service.GoGraphAssembler;
import io.yuan.octopus.service.GraphStoreService;
import io.yuan.octopus.service.MetadataStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Go language adapter for the analysis pipeline (see {@link ProjectAnalyzer}).
 *
 * <p>Deep analysis delegates to the external go-project-analyzer HTTP service
 * (go/packages + go/types — the real Go type checker) via
 * {@link GoAnalyzerExecutor}; there is no embedded Go parser, so an
 * unavailable service degrades ALL modules (no JavaParser-style fallback).
 *
 * <p>Storage conventions match the JVM pipeline: per-module snapshots in
 * Neo4j (MethodNode/CALLS keyed by signature), class/method metadata in MySQL
 * (named types and package pseudo-classes as class_info rows).
 */
@Service
public class GoProjectAnalyzer implements ProjectAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GoProjectAnalyzer.class);

    /** Bound for recursive go.mod discovery (mirrors the Go side's limit). */
    private static final int MAX_GO_MOD_DEPTH = 4;

    private final GoAnalyzerExecutor goAnalyzerExecutor;
    private final GoGraphAssembler goGraphAssembler;
    private final GraphStoreService graphStoreService;
    private final MetadataStoreService metadataStoreService;
    private final AnalyzedProjectRepository analyzedProjectRepository;

    public GoProjectAnalyzer(GoAnalyzerExecutor goAnalyzerExecutor,
                             GoGraphAssembler goGraphAssembler,
                             GraphStoreService graphStoreService,
                             MetadataStoreService metadataStoreService,
                             AnalyzedProjectRepository analyzedProjectRepository) {
        this.goAnalyzerExecutor = goAnalyzerExecutor;
        this.goGraphAssembler = goGraphAssembler;
        this.graphStoreService = graphStoreService;
        this.metadataStoreService = metadataStoreService;
        this.analyzedProjectRepository = analyzedProjectRepository;
    }

    @Override
    public String getLanguage() {
        return "GO";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean supports(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return false;
        if (hasGoMod(projectRoot)) return true;
        // A workspace may hold Go modules one level down (like JvmProjectAnalyzer).
        try (Stream<Path> children = Files.list(projectRoot)) {
            return children.filter(Files::isDirectory).anyMatch(this::hasGoMod);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasGoMod(Path dir) {
        return Files.exists(dir.resolve("go.mod"));
    }

    // -------------------------------------------------------------------------
    // Module discovery
    // -------------------------------------------------------------------------

    @Override
    public List<DiscoveredProject> scanProjects(RepoSnapshot snapshot) {
        Path root = snapshot.repoLocalPath();
        List<DiscoveredProject> modules = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, MAX_GO_MOD_DEPTH)) {
            walk.filter(Files::isDirectory)
                    .filter(this::isScannableDir)
                    .filter(this::hasGoMod)
                    .forEach(dir -> modules.add(createGoProject(dir)));
        } catch (IOException e) {
            log.error("Failed to scan for Go modules under {}: {}", root, e.getMessage());
        }
        return modules;
    }

    @Override
    public DiscoveredProject scanSingleProject(Path projectRoot, String projectName) {
        return new DiscoveredProject(projectName, projectRoot, null,
                List.of(projectRoot), null);
    }

    private boolean isScannableDir(Path dir) {
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
        return !name.equals("vendor") && !name.equals("testdata")
                && !name.equals(".git") && !name.equals("node_modules");
    }

    /**
     * Go modules are source-tree-wide (no src/main root): the module directory
     * itself is the single source directory.
     */
    private DiscoveredProject createGoProject(Path moduleDir) {
        return new DiscoveredProject(
                moduleDir.getFileName().toString(),
                moduleDir,
                null,             // buildTool: Maven/Gradle enum does not apply
                List.of(moduleDir),
                null);            // javaVersion: not applicable
    }

    // -------------------------------------------------------------------------
    // Deep analysis
    // -------------------------------------------------------------------------

    @Override
    public List<String> analyzeDeep(AnalysisContext ctx,
                                    List<DiscoveredProject> projects,
                                    RepoSnapshot snapshot) {
        List<String> degraded = new ArrayList<>();
        if (projects.isEmpty()) return degraded;

        List<AnalyzedProjectEntity> entities = new ArrayList<>(projects.size());
        for (DiscoveredProject project : projects) {
            var entity = findOrCreateEntity(project, ctx);
            entity.setRepositoryRootPath(io.yuan.octopus.service.RepositoryIdentity.root(ctx.projectRoot().toString()));
            analyzedProjectRepository.save(entity);
            entities.add(entity);
        }

        // Workspace-wide analysis: the Go service resolves interfaces and
        // cross-module calls against the whole tree in one pass.
        GoAnalysisOutput output = goAnalyzerExecutor.execute(ctx.projectRoot());
        if (output == null) {
            log.warn("Go analyzer unavailable for workspace {}; all {} modules degraded (no fallback parser)",
                    ctx.projectRoot(), projects.size());
            return projects.stream().map(DiscoveredProject::projectName).collect(Collectors.toList());
        }
        log.info("Global Go analysis for workspace {}: {} types, {} methods, {} edges",
                ctx.projectRoot(), output.types().size(), output.methods().size(), output.edges().size());

        CallGraph fullGraph = goGraphAssembler.buildCallGraph(output);
        ParseResult fullMetadata = goGraphAssembler.buildParseResult(output);
        List<Long> allProjectIds = entities.stream().map(AnalyzedProjectEntity::getId).toList();

        // Phase 1: per-module nodes + metadata. Phase 2: edges after all
        // modules' nodes exist (cross-module callee resolution).
        record GraphEntry(AnalyzedProjectEntity entity, CallGraph callGraph) {}
        List<GraphEntry> graphEntries = new ArrayList<>();

        for (int i = 0; i < projects.size(); i++) {
            DiscoveredProject project = projects.get(i);
            AnalyzedProjectEntity entity = entities.get(i);

            CallGraph moduleGraph = filterGraphForModule(fullGraph, project);
            ParseResult moduleMetadata = filterMetadataForModule(fullMetadata, project);

            graphStoreService.clearProjectGraph(entity.getId(), ctx.branch(), ctx.commitId());
            graphStoreService.storeNodes(entity.getId(), ctx.branch(), ctx.commitId(), moduleGraph.nodes());
            metadataStoreService.storeMetadata(entity.getId(), ctx.branch(), ctx.commitId(), moduleMetadata);

            log.info("Stored Go graph for module {}: {} nodes, {} edges",
                    project.projectName(), moduleGraph.nodes().size(), moduleGraph.edges().size());
            graphEntries.add(new GraphEntry(entity, moduleGraph));
        }

        for (GraphEntry entry : graphEntries) {
            graphStoreService.storeEdges(entry.entity().getId(), ctx.branch(), ctx.commitId(),
                    allProjectIds, entry.callGraph().edges());
        }

        markProjectsAnalyzed(projects, ctx);
        return degraded;
    }

    /**
     * Keeps nodes whose source file lives under the module directory, plus
     * edges whose CALLER is in the module (cross-module callees stay as nodes,
     * matching the JVM module filtering semantics).
     */
    private CallGraph filterGraphForModule(CallGraph fullGraph, DiscoveredProject project) {
        String dirPrefix = project.projectPath().toAbsolutePath().normalize().toString() + "/";
        Set<MethodNode> moduleNodes = fullGraph.nodes().stream()
                .filter(n -> n.sourceFilePath() != null && n.sourceFilePath().startsWith(dirPrefix))
                .collect(Collectors.toSet());
        Set<String> moduleSignatures = moduleNodes.stream().map(MethodNode::signature).collect(Collectors.toSet());

        Set<CallEdge> moduleEdges = fullGraph.edges().stream()
                .filter(e -> moduleSignatures.contains(e.caller().signature()))
                .collect(Collectors.toSet());
        for (CallEdge edge : moduleEdges) {
            if (!moduleSignatures.contains(edge.callee().signature())) {
                moduleNodes.add(edge.callee());
            }
        }
        return new CallGraph(moduleNodes, moduleEdges);
    }

    /** Keeps ParsedFiles whose path is under the module directory. */
    private ParseResult filterMetadataForModule(ParseResult fullMetadata, DiscoveredProject project) {
        String dirPrefix = project.projectPath().toAbsolutePath().normalize().toString() + "/";
        List<ParsedFile> files = fullMetadata.parsedFiles().stream()
                .filter(f -> f.filePath() != null && f.filePath().startsWith(dirPrefix))
                .map(f -> filterFileClasses(f, dirPrefix))
                .toList();
        return new ParseResult(files, fullMetadata.errors());
    }

    /**
     * Pseudo package classes carry free functions from every file of the
     * package; keep only methods whose own file is inside the module to avoid
     * cross-module method duplication.
     */
    private ParsedFile filterFileClasses(ParsedFile file, String dirPrefix) {
        boolean changed = false;
        List<ParsedClass> classes = new ArrayList<>(file.classes().size());
        for (ParsedClass c : file.classes()) {
            if (c.sourceFilePath() == null || c.sourceFilePath().isEmpty()
                    || c.sourceFilePath().startsWith(dirPrefix)) {
                classes.add(c);
            } else {
                changed = true;
            }
        }
        return changed ? new ParsedFile(file.filePath(), file.packageName(), file.imports(), classes) : file;
    }

    // -------------------------------------------------------------------------
    // Entity helpers (same keys as the JVM analyzer)
    // -------------------------------------------------------------------------

    private AnalyzedProjectEntity findOrCreateEntity(DiscoveredProject project, AnalysisContext ctx) {
        if (ctx.repoConfigId() == null) {
            String canonicalPath = project.projectPath().toAbsolutePath().normalize().toString();
            // 身份 = 路径（同仓库一个 project）；branch 是最近一次分析的展示字段，
            // 多分支数据按 branch 列共存，版本枚举由元数据查询提供
            return analyzedProjectRepository.findByRepoConfigIdIsNullAndProjectPath(canonicalPath)
                    .map(entity -> {
                        entity.setBranch(ctx.branch());
                        entity.setCommitId(ctx.commitId());
                        return analyzedProjectRepository.save(entity);
                    })
                    .orElseGet(() -> {
                        AnalyzedProjectEntity entity = AnalyzedProjectEntity.builder()
                                .repoConfigId(null)
                                .branch(ctx.branch())
                                .projectName(project.projectName())
                                .projectPath(canonicalPath)
                                .commitId(ctx.commitId())
                                .build();
                        return analyzedProjectRepository.save(entity);
                    });
        }
        return analyzedProjectRepository
                .findByRepoConfigIdAndBranchAndProjectName(ctx.repoConfigId(), ctx.branch(), project.projectName())
                .orElseGet(() -> {
                    AnalyzedProjectEntity entity = AnalyzedProjectEntity.builder()
                            .repoConfigId(ctx.repoConfigId())
                            .branch(ctx.branch())
                            .projectName(project.projectName())
                            .projectPath(project.projectPath().toString())
                            .build();
                    return analyzedProjectRepository.save(entity);
                });
    }

    private void markProjectsAnalyzed(List<DiscoveredProject> projects, AnalysisContext ctx) {
        LocalDateTime now = LocalDateTime.now();
        for (DiscoveredProject project : projects) {
            if (ctx.repoConfigId() == null) {
                String canonicalPath = project.projectPath().toAbsolutePath().normalize().toString();
                analyzedProjectRepository.findByRepoConfigIdIsNullAndProjectPath(canonicalPath)
                        .ifPresent(entity -> {
                            entity.setCommitId(ctx.commitId());
                            entity.setAnalyzedAt(now);
                            analyzedProjectRepository.save(entity);
                        });
            } else {
                analyzedProjectRepository
                        .findByRepoConfigIdAndBranchAndProjectName(ctx.repoConfigId(), ctx.branch(), project.projectName())
                        .ifPresent(entity -> {
                            entity.setCommitId(ctx.commitId());
                            entity.setAnalyzedAt(now);
                            analyzedProjectRepository.save(entity);
                        });
            }
        }
    }
}
