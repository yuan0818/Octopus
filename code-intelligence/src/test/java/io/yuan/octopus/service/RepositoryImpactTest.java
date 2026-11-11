package io.yuan.octopus.service;

import io.yuan.octopus.entity.*;
import io.yuan.octopus.repository.*;
import io.yuan.octopus.dto.response.EndpointDTO;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepositoryImpactTest {
    @TempDir Path root;

    @Test void repositoryRequestFindsChangesInBothModulesAndCrossModuleCallers() throws Exception {
        Path apiFile = root.resolve("api/Controller.java"), libFile = root.resolve("shared/Rules.java");
        Files.createDirectories(apiFile.getParent()); Files.createDirectories(libFile.getParent());
        String base, target;
        try (var git = Git.init().setDirectory(root.toFile()).call()) {
            Files.writeString(apiFile, "class Controller { int get() { return 1; } }\n");
            Files.writeString(libFile, "class Rules { int check() { return 1; } }\n");
            git.add().addFilepattern(".").call();
            base = git.commit().setMessage("base").setAuthor("Test", "test@example.invalid").call().getName();
            Files.writeString(apiFile, "class Controller { int get() { return 2; } }\n");
            Files.writeString(libFile, "class Rules { int check() { return 2; } }\n");
            git.add().addFilepattern(".").call();
            target = git.commit().setMessage("target").setAuthor("Test", "test@example.invalid").call().getName();
        }
        var projects = mock(AnalyzedProjectRepository.class);
        var classes = mock(ClassInfoRepository.class);
        var methods = mock(MethodInfoRepository.class);
        var tasks = mock(AnalysisTaskRepository.class);
        var steps = mock(AnalysisTaskStepRepository.class);
        var query = mock(QueryService.class);
        var driver = mock(Driver.class);
        var session = mock(Session.class);
        var api = AnalyzedProjectEntity.builder().id(1L).projectName("api")
                .projectPath(apiFile.getParent().toString()).branch("main").build();
        var lib = AnalyzedProjectEntity.builder().id(2L).projectName("shared")
                .projectPath(libFile.getParent().toString()).branch("main").build();
        when(projects.findAll()).thenReturn(List.of(api, lib));
        when(projects.findAllById(List.of(1L, 2L))).thenReturn(List.of(api, lib));
        when(classes.findAnalyzedBranches(anyLong())).thenReturn(List.of("main"));
        when(classes.findByProjectIdAndBranchAndCommitId(anyLong(), eq("main"), anyString()))
                .thenAnswer(call -> List.of(ClassInfo.builder().id(call.getArgument(0)).build()));
        when(methods.findByClassInfoIdIn(anyList())).thenAnswer(call -> {
            long id = ((Number) ((List<?>) call.getArgument(0)).getFirst()).longValue();
            return List.of(MethodInfo.builder().methodSignature(id == 1 ? "Controller.get()" : "Rules.check()")
                    .filePath((id == 1 ? apiFile : libFile).toString())
                    .lineStart(1).lineEnd(1).build());
        });
        when(query.getEndpoints(eq(1L), eq("main"), anyString())).thenAnswer(call -> List.of(
                new EndpointDTO("GET", "/example", "get", "Controller", "Controller", "Controller.get()",
                        1L, "api", "main", call.getArgument(2))));
        when(tasks.findByCommitIdAndBranchOrderByIdDesc(anyString(), eq("main"))).thenReturn(List.of(
                AnalysisTask.builder().id(1L).status("COMPLETED").build()));
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenAnswer(call -> {
            Map<String, Object> parameters = call.getArgument(1);
            assertEquals(List.of(1L, 2L), parameters.get("ids"));
            List<Map<String, Object>> rows = ((String) call.getArgument(0)).contains("RETURN m.projectId")
                    ? List.of(Map.of("pid", 1L, "sig", "Controller.get()"), Map.of("pid", 2L, "sig", "Rules.check()"))
                    : List.of(Map.of("ap", 1L, "asig", "Controller.get()", "bp", 2L, "bsig", "Rules.check()", "kind", "DIRECT", "line", 1));
            return result(rows);
        });
        var scopes = new RepositoryScopeService(projects, classes, mock(RepoConfigRepository.class));
        var service = new BusinessTestImpactService(projects, classes, methods,
                mock(GitService.class, CALLS_REAL_METHODS), query, driver, tasks, steps, scopes);
        var report = service.analyze(new BusinessTestImpactService.Request(null, "main", base, target,
                RepositoryScopeService.key(api)));
        assertEquals(List.of(1L, 2L), report.projectIds());
        assertEquals("STATIC_GRAPH", report.coverage(), report.warnings().toString());
        assertEquals(4, report.evidence().size()); // Two changed modules, on both sides.
        assertTrue(report.evidence().stream().anyMatch(e -> e.changedMethod().projectId() == 2L
                && e.endpoint().projectId() == 1L && e.path().size() == 1));
    }

    private static Result result(List<Map<String, Object>> rows) {
        var iterator = rows.iterator();
        var result = mock(Result.class);
        when(result.hasNext()).thenAnswer(call -> iterator.hasNext());
        when(result.next()).thenAnswer(call -> {
            var values = iterator.next();
            var record = mock(org.neo4j.driver.Record.class);
            when(record.get(anyString())).thenAnswer(get -> Values.value(values.get(get.getArgument(0))));
            return record;
        });
        return result;
    }
}
