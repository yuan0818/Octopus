package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;

import io.yuan.octopus.dto.response.EndpointDTO;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.repository.*;
import io.yuan.octopus.service.*;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.*;
import org.neo4j.harness.*;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

/**
 * Real Git diff + scoped Neo4j calls + persisted business graph + pinned IR. Metadata is an
 * explicit analyzer fixture.
 */
class BusinessImpactPipelineIntegrationTest {
    @TempDir Path directory;

    @Test
    void deletedGuardReachesOnlyTheCorrectVersionedApiAndItsConfirmedTestPoint() throws Exception {
        try (Git repository = Git.init().setDirectory(directory.toFile()).call();
                Neo4j graph = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
                Driver driver = GraphDatabase.driver(graph.boltURI(), AuthTokens.none())) {
            Path source = directory.resolve("Login.java");
            Files.writeString(
                    source,
                    "class Login {\n"
                            + " void authenticate() {\n"
                            + "  checkEnabled();\n"
                            + "  issueToken();\n"
                            + " }\n"
                            + "}\n");
            repository.add().addFilepattern(".").call();
            String base =
                    repository
                            .commit()
                            .setAuthor("Fixture", "test@example.invalid")
                            .setMessage("base")
                            .call()
                            .name();
            Files.writeString(
                    source, "class Login {\n void authenticate() {\n  issueToken();\n }\n}\n");
            Files.writeString(directory.resolve("policy.yaml"), "enabled: false\n");
            repository.add().addFilepattern(".").call();
            String target =
                    repository
                            .commit()
                            .setAuthor("Fixture", "test@example.invalid")
                            .setMessage("remove guard")
                            .call()
                            .name();
            var projects = mock(AnalyzedProjectRepository.class);
            var classes = mock(ClassInfoRepository.class);
            var methods = mock(MethodInfoRepository.class);
            var query = mock(QueryService.class);
            var tasks = mock(AnalysisTaskRepository.class);
            var taskSteps = mock(AnalysisTaskStepRepository.class);
            var project = new AnalyzedProjectEntity();
            project.setId(1L);
            project.setRepoConfigId(9L);
            project.setProjectPath(directory.toString());
            when(projects.findAllById(List.of(1L))).thenReturn(List.of(project));
            var clazz = new ClassInfo();
            clazz.setId(10L);
            clazz.setProjectId(1L);
            clazz.setSourceFilePath(source.toString());
            var method = new MethodInfo();
            method.setId(20L);
            method.setClassInfo(clazz);
            method.setFilePath(source.toString());
            method.setMethodSignature("Login.authenticate()");
            method.setLineStart(2);
            method.setLineEnd(5);
            when(classes.findByProjectIdAndBranchAndCommitId(eq(1L), eq("main"), anyString()))
                    .thenReturn(List.of(clazz));
            when(methods.findByClassInfoIdIn(List.of(10L))).thenReturn(List.of(method));
            for (String commit : List.of(base, target)) {
                var task = new AnalysisTask();
                task.setId(commit.equals(base) ? 30L : 31L);
                task.setRepoConfigId(9L);
                task.setStatus("COMPLETED");
                when(tasks.findByCommitIdAndBranchOrderByIdDesc(commit, "main"))
                        .thenReturn(List.of(task));
                when(query.getEndpoints(1L, "main", commit))
                        .thenReturn(
                                List.of(
                                        new EndpointDTO(
                                                "POST",
                                                "/login",
                                                "login",
                                                "Controller",
                                                "Controller",
                                                "Controller.login()",
                                                1L,
                                                "auth",
                                                "main",
                                                commit),
                                        new EndpointDTO(
                                                "GET",
                                                "/unrelated",
                                                "unrelated",
                                                "Controller",
                                                "Controller",
                                                "Controller.unrelated()",
                                                1L,
                                                "auth",
                                                "main",
                                                commit)));
                try (var session = driver.session()) {
                    session.run(
                                    """
                                    CREATE (m:MethodNode {projectId:1,branch:'main',commitId:$commit,signature:'Login.authenticate()'})
                                    CREATE (a:MethodNode {projectId:1,branch:'main',commitId:$commit,signature:'Controller.login()'})
                                    CREATE (a)-[:CALLS {branch:'main',commitId:$commit,callType:'DIRECT',lineNumber:10}]->(m)
                                    CREATE (wrong:MethodNode {projectId:2,branch:'other',commitId:$commit,signature:'Controller.unrelated()'})
                                    CREATE (wrong)-[:CALLS {branch:'main',commitId:$commit,callType:'DIRECT'}]->(m)
                                    """,
                                    Map.of("commit", commit))
                            .consume();
                }
            }
            var service =
                    new BusinessTestImpactService(
                            projects,
                            classes,
                            methods,
                            new GitService(null, directory.toString(), false),
                            query,
                            driver,
                            tasks,
                            taskSteps,
                            mock(RepositoryScopeService.class));
            var report =
                    service.analyze(
                            new BusinessTestImpactService.Request(
                                    List.of(1L), "main", base, target));
            assertEquals(1, report.evidence().size());
            assertEquals("BASE", report.evidence().getFirst().side());
            assertEquals("/login", report.evidence().getFirst().endpoint().path());
            assertArrayEquals(new int[] {3, 3}, report.evidence().getFirst().ranges().getFirst());
            assertEquals("PARTIAL", report.coverage());
            assertTrue(report.warnings().contains("UNSUPPORTED_CHANGED_FILE:policy.yaml"));
            var store = new BusinessKnowledgeStore(driver);
            var login = TestingFixtures.login();
            store.saveOperation(
                    new Operation(
                            login.operationId(),
                            login.revision(),
                            login.system(),
                            login.service(),
                            login.method(),
                            login.path(),
                            login.inputs(),
                            login.requestSchema(),
                            login.responseSchema(),
                            List.of(new Implementation(1, "main", base, "Controller.login( )")),
                            login.source(),
                            login.requiresCapabilities(),
                            null));
            try (var session = driver.session()) {
                assertEquals(
                        1,
                        session.run(
                                        "MATCH (:ApiOperation)-[r:IMPLEMENTED_BY]->(:MethodNode)"
                                            + " RETURN count(r) AS count")
                                .single()
                                .get("count")
                                .asInt());
            }
            store.saveOperation(TestingFixtures.verify());
            var draft = store.saveDraft(TestingFixtures.flow());
            store.decide("twofa", 1, draft.hash(), "reviewer", true);
            var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext(
                    "/api/query/business-test-impact",
                    e -> {
                        byte[] data =
                                TestingJson.canonical(Map.of("data", report))
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        e.sendResponseHeaders(200, data.length);
                        e.getResponseBody().write(data);
                        e.close();
                    });
            http.start();
            try {
                var impact =
                        new BusinessTestImpactBridge(
                                        store, "http://127.0.0.1:" + http.getAddress().getPort())
                                .analyze(
                                        Map.of(
                                                "projectIds",
                                                List.of(1),
                                                "branch",
                                                "main",
                                                "baseCommitId",
                                                base,
                                                "targetCommitId",
                                                target));
                assertEquals(1, impact.testPoints().size());
                var point = impact.testPoints().getFirst();
                assertEquals(List.of("login", "verify"), point.affectedNodes());
                var validator = new IrValidator(new CapabilityRegistry());
                var ir =
                        new ScenarioPlanner(store, validator)
                                .generate(
                                        point.flowId(),
                                        point.flowRevision(),
                                        point.testPointId(),
                                        point.evidenceRefs());
                assertEquals(
                        List.of("login", "verify"),
                        validator.validate(ir).stream().map(Step::id).toList());
                assertEquals(1, store.evidenceSnapshots(ir.changeEvidenceRefs()).size());
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                store.validateEvidence(
                                        "other-flow", 1, "normal", point.evidenceRefs()));
                var jmx =
                        new JmxCompiler(validator, new CapabilityRegistry())
                                .compile(
                                        ir,
                                        new ExecutionProfile(
                                                Map.of(
                                                        "account.auth",
                                                        new Target("http://127.0.0.1:8999")),
                                                Map.of(),
                                                Map.of()));
                assertEquals(TestingJson.hash(ir), jmx.irHash());
            } finally {
                http.stop(0);
            }
        }
    }
}
