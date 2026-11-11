package io.yuan.octopus.scenario.testing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class ProjectContextExecutionDefaultsTest {
    Operation mapped(Operation o, long project) {
        return new Operation(o.operationId(), o.revision(), o.system(), o.service(), o.method(), o.path(), o.inputs(), o.requestSchema(), o.responseSchema(),
                List.of(new Implementation(project, "master", "a".repeat(40), "entry")), o.source(), o.requiresCapabilities(), o.securityProfile());
    }
    ProjectContextController controller(String firstUrl, String secondUrl) {
        return controller(firstUrl, secondUrl, List.of(), List.of(), TestingFixtures.ir().initialContext());
    }
    ProjectContextController controller(String firstUrl, String secondUrl, List<ProjectContextService.EnvironmentVariable> firstVariables,
                                       List<ProjectContextService.EnvironmentVariable> secondVariables, Map<String, Variable> declarations) {
        var service = mock(ProjectContextService.class); var catalog = mock(RepositoryCatalogClient.class); var execution = mock(BusinessTestExecutionService.class);
        var ir = TestingFixtures.ir();
        when(execution.load("scenario")).thenReturn(new TestIR(ir.irVersion(), ir.scenarioId(), ir.flowId(), ir.flowRevision(), ir.flowHash(), ir.testPointId(), ir.oracleHash(),
                declarations, List.of(mapped(TestingFixtures.login(), 1), mapped(TestingFixtures.verify(), 2)), ir.stateModels(), ir.steps(), ir.dependencies(), ir.requiresCapabilities(), null, List.of()));
        var modules = List.of(new RepositoryCatalogClient.Module(1, "auth", "auth", "master", List.of("master")),
                new RepositoryCatalogClient.Module(2, "other", "other", "master", List.of("master")));
        when(catalog.scopes()).thenReturn(List.of(new RepositoryCatalogClient.Scope("repo:1", "project", "/repo", modules)));
        for (var entry : Map.of("auth", firstUrl, "other", secondUrl).entrySet())
            when(service.resolve("repo:1", entry.getKey(), "test")).thenReturn(new ProjectContextService.Resolved(null,
                    new ProjectContextService.Definition("", "", entry.getValue(), List.of(), entry.getKey().equals("auth") ? firstVariables : secondVariables), Map.of(), true, true));
        return new ProjectContextController(service, new ExecutionDefaultsService(service, catalog, execution));
    }
    @Test void usesSavedModuleOrInheritedUrlsForThePinnedOperations() {
        var result = controller("https://test.example.com", "https://test.example.com").defaults(new ExecutionDefaultsService.DefaultsRequest("scenario", "test"));
        assertEquals(Map.of("account.auth", new Target("https://test.example.com")), result.services());
        assertTrue(result.warnings().isEmpty());
    }
    @Test void ambiguousOrMissingTargetsMustBeResolvedByTheUser() {
        var conflicting = controller("https://one.example.com", "https://two.example.com").defaults(new ExecutionDefaultsService.DefaultsRequest("scenario", "test"));
        assertTrue(conflicting.services().isEmpty()); assertTrue(conflicting.warnings().getFirst().contains("多个"));
        var missing = controller("", "").defaults(new ExecutionDefaultsService.DefaultsRequest("scenario", "test"));
        assertTrue(missing.services().isEmpty()); assertTrue(missing.warnings().getFirst().contains("未配置"));
    }
    @Test void loadsOnlyDeclaredMatchingVariablesAndDeduplicatesSharedValues() {
        var variables = List.of(new ProjectContextService.EnvironmentVariable("tenant", "string", "tenant-value"), new ProjectContextService.EnvironmentVariable("unused", "boolean", true));
        var result = controller("https://test.example.com", "https://test.example.com", variables, variables, Map.of("tenant", new Variable("string", false)))
                .defaults(new ExecutionDefaultsService.DefaultsRequest("scenario", "test"));
        assertEquals(Map.of("tenant", "tenant-value"), result.initialContext());
        assertTrue(result.warnings().isEmpty());
    }
    @Test void conflictingModuleValuesAndTypeMismatchesDoNotSilentlyChooseOne() {
        var a = List.of(new ProjectContextService.EnvironmentVariable("tenant", "string", "first-value"));
        var b = List.of(new ProjectContextService.EnvironmentVariable("tenant", "string", "second-value"));
        var conflict = controller("https://test.example.com", "https://test.example.com", a, b, Map.of("tenant", new Variable("string", false)))
                .defaults(new ExecutionDefaultsService.DefaultsRequest("scenario", "test"));
        assertTrue(conflict.initialContext().isEmpty()); assertTrue(conflict.warnings().getFirst().contains("tenant"));
        assertFalse(conflict.warnings().getFirst().contains("first-value"));
        var mismatch = controller("https://test.example.com", "https://test.example.com", a, a, Map.of("tenant", new Variable("number", false)))
                .defaults(new ExecutionDefaultsService.DefaultsRequest("scenario", "test"));
        assertTrue(mismatch.initialContext().isEmpty()); assertTrue(mismatch.warnings().getFirst().contains("类型"));
    }
}
