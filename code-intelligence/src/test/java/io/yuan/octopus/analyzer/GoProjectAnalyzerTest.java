package io.yuan.octopus.analyzer;

import io.yuan.octopus.domain.CallGraph;
import io.yuan.octopus.domain.DiscoveredProject;
import io.yuan.octopus.domain.GoAnalysisOutput;
import io.yuan.octopus.domain.GoAnalysisOutput.GoMethodNode;
import io.yuan.octopus.entity.AnalyzedProjectEntity;
import io.yuan.octopus.entity.RepoSnapshot;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import io.yuan.octopus.service.GoAnalyzerExecutor;
import io.yuan.octopus.service.GoGraphAssembler;
import io.yuan.octopus.service.GraphStoreService;
import io.yuan.octopus.service.MetadataStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoProjectAnalyzerTest {

    private GoAnalyzerExecutor goAnalyzerExecutor;
    private GraphStoreService graphStoreService;
    private MetadataStoreService metadataStoreService;
    private AnalyzedProjectRepository analyzedProjectRepository;
    private GoProjectAnalyzer analyzer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        goAnalyzerExecutor = mock(GoAnalyzerExecutor.class);
        graphStoreService = mock(GraphStoreService.class);
        metadataStoreService = mock(MetadataStoreService.class);
        analyzedProjectRepository = mock(AnalyzedProjectRepository.class);
        // findOrCreateLocalEntity re-saves found entities; echo them back.
        when(analyzedProjectRepository.save(any(AnalyzedProjectEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        analyzer = new GoProjectAnalyzer(goAnalyzerExecutor, new GoGraphAssembler(),
                graphStoreService, metadataStoreService, analyzedProjectRepository);
    }

    // ── supports() ──────────────────────────────────────────────────────────

    @Test
    void shouldSupportDirectoryWithGoModAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("go.mod"), "module demo\n");
        assertTrue(analyzer.supports(tempDir));
    }

    @Test
    void shouldSupportDirectoryWithGoModInImmediateChild() throws IOException {
        Path child = Files.createDirectories(tempDir.resolve("svc"));
        Files.writeString(child.resolve("go.mod"), "module demo/svc\n");
        assertTrue(analyzer.supports(tempDir));
    }

    @Test
    void shouldNotSupportPlainDirectory() {
        assertFalse(analyzer.supports(tempDir));
    }

    @Test
    void shouldNotSupportJvmProject() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        assertFalse(analyzer.supports(tempDir));
    }

    // ── scanProjects() ──────────────────────────────────────────────────────

    @Test
    void shouldScanEachGoModAsModule() throws IOException {
        Path svc = Files.createDirectories(tempDir.resolve("svc"));
        Files.writeString(svc.resolve("go.mod"), "module demo/svc\n");
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("go.mod"), "module demo/lib\n");

        List<DiscoveredProject> modules = analyzer.scanProjects(
                new RepoSnapshot(tempDir, "http://x", null, null));

        assertEquals(2, modules.size());
        DiscoveredProject first = modules.get(0);
        assertEquals("lib", first.projectName());
        assertEquals(List.of(lib), first.sourceDirectories());
        assertEquals("svc", modules.get(1).projectName());
        assertNull(first.buildTool());
        assertNull(first.javaVersion());
    }

    // ── analyzeDeep() ───────────────────────────────────────────────────────

    private GoAnalysisOutput output(Path moduleDir) {
        String sig = "demo.example.com/demo/api.HandleRequest(context.Context,string):error";
        GoMethodNode handler = new GoMethodNode(
                "demo.example.com/demo/api", "HandleRequest", sig,
                List.of("context.Context", "string"), List.of("error"), "",
                moduleDir.resolve("api").resolve("api.go").toString(),
                "func HandleRequest() {}", 10, 20);
        return new GoAnalysisOutput(List.of(), List.of(handler), List.of(), List.of());
    }

    private AnalysisContext ctx(Path root) {
        return new AnalysisContext(root, "local", "abc123", null, 1L);
    }

    @Test
    void shouldStoreGraphAndMetadataWhenAnalyzerSucceeds() throws IOException {
        Path moduleDir = Files.createDirectories(tempDir.resolve("demo"));
        Files.writeString(moduleDir.resolve("go.mod"), "module demo\n");
        when(goAnalyzerExecutor.execute(any())).thenReturn(output(moduleDir));
        AnalyzedProjectEntity entity = AnalyzedProjectEntity.builder()
                .id(42L).repoConfigId(null).branch("local")
                .projectName("demo").projectPath(moduleDir.toString()).build();
        when(analyzedProjectRepository.findByRepoConfigIdIsNullAndProjectPath(anyString()))
                .thenReturn(Optional.of(entity));

        List<String> degraded = analyzer.analyzeDeep(
                ctx(tempDir),
                List.of(new DiscoveredProject("demo", moduleDir, null, List.of(moduleDir), null)),
                new RepoSnapshot(tempDir, "http://x", "abc123", null));

        assertTrue(degraded.isEmpty());
        verify(graphStoreService).clearProjectGraph(42L, "local", "abc123");
        verify(graphStoreService).storeNodes(eq(42L), eq("local"), eq("abc123"), anySet());
        verify(metadataStoreService).storeMetadata(eq(42L), eq("local"), eq("abc123"), any());
        verify(graphStoreService).storeEdges(eq(42L), eq("local"), eq("abc123"), eq(List.of(42L)), anySet());
    }

    @Test
    void shouldDegradeAllModulesWhenAnalyzerUnavailable() throws IOException {
        Path moduleDir = Files.createDirectories(tempDir.resolve("demo"));
        Files.writeString(moduleDir.resolve("go.mod"), "module demo\n");
        when(goAnalyzerExecutor.execute(any())).thenReturn(null);

        List<String> degraded = analyzer.analyzeDeep(
                ctx(tempDir),
                List.of(new DiscoveredProject("demo", moduleDir, null, List.of(moduleDir), null)),
                new RepoSnapshot(tempDir, "http://x", "abc123", null));

        assertEquals(List.of("demo"), degraded);
        verify(graphStoreService, never()).storeNodes(anyLong(), anyString(), anyString(), any());
        verify(metadataStoreService, never()).storeMetadata(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void shouldFilterGraphPerModuleBySourcePath() throws IOException {
        Path demoDir = Files.createDirectories(tempDir.resolve("demo"));
        Files.writeString(demoDir.resolve("go.mod"), "module demo\n");
        Path otherDir = Files.createDirectories(tempDir.resolve("other"));
        Files.writeString(otherDir.resolve("go.mod"), "module other\n");

        GoMethodNode demoFunc = new GoMethodNode("demo/api", "Run",
                "demo/api.Run():", List.of(), List.of(), "",
                demoDir.resolve("api").resolve("a.go").toString(), "func Run() {}", 1, 2);
        GoMethodNode otherFunc = new GoMethodNode("other/b", "Walk",
                "other/b.Walk():", List.of(), List.of(), "",
                otherDir.resolve("b").resolve("b.go").toString(), "func Walk() {}", 1, 2);
        when(goAnalyzerExecutor.execute(any())).thenReturn(
                new GoAnalysisOutput(List.of(), List.of(demoFunc, otherFunc), List.of(), List.of()));

        AnalyzedProjectEntity demoEntity = AnalyzedProjectEntity.builder()
                .id(1L).branch("local").projectName("demo").projectPath(demoDir.toString()).build();
        AnalyzedProjectEntity otherEntity = AnalyzedProjectEntity.builder()
                .id(2L).branch("local").projectName("other").projectPath(otherDir.toString()).build();
        when(analyzedProjectRepository.findByRepoConfigIdIsNullAndProjectPath(
                eq(demoDir.toAbsolutePath().normalize().toString()))).thenReturn(Optional.of(demoEntity));
        when(analyzedProjectRepository.findByRepoConfigIdIsNullAndProjectPath(
                eq(otherDir.toAbsolutePath().normalize().toString()))).thenReturn(Optional.of(otherEntity));

        analyzer.analyzeDeep(ctx(tempDir),
                List.of(new DiscoveredProject("demo", demoDir, null, List.of(demoDir), null),
                        new DiscoveredProject("other", otherDir, null, List.of(otherDir), null)),
                new RepoSnapshot(tempDir, "http://x", "abc123", null));

        // Each module stores only its own nodes.
        verify(graphStoreService).storeNodes(eq(1L), eq("local"), eq("abc123"), argThat(
                nodes -> nodes.size() == 1 && ((CallGraph) null) == null
                        && nodes.iterator().next().signature().equals("demo/api.Run():")));
        verify(graphStoreService).storeNodes(eq(2L), eq("local"), eq("abc123"), argThat(
                nodes -> nodes.size() == 1 && nodes.iterator().next().signature().equals("other/b.Walk():")));
    }
}
