package io.yuan.octopus.scenario.testing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Phase 1A：批量回归的判定汇总、输入阻断、并发上限与恢复语义。 */
class RegressionBatchServiceTest {

    private RegressionBatchRepository batches;
    private BusinessTestImpactBridge impact;
    private ScenarioPlanner planner;
    private BusinessTestExecutionService execution;
    private ExecutionDefaultsService defaults;
    private BusinessTestRunRepository runs;
    private RegressionBatchService service;
    private final Map<String, RegressionBatch> store = new HashMap<>();
    private final Map<String, BusinessTestRun> runStore = new HashMap<>();
    private static final String SHA_A = "a".repeat(40), SHA_B = "b".repeat(40);

    @BeforeEach
    void setUp() {
        batches = mock(RegressionBatchRepository.class);
        impact = mock(BusinessTestImpactBridge.class);
        planner = mock(ScenarioPlanner.class);
        execution = mock(BusinessTestExecutionService.class);
        defaults = mock(ExecutionDefaultsService.class);
        runs = mock(BusinessTestRunRepository.class);
        when(batches.save(any())).thenAnswer(i -> { RegressionBatch b = i.getArgument(0); store.put(b.id, b); return b; });
        when(batches.findById(anyString())).thenAnswer(i -> Optional.ofNullable(store.get((String) i.getArgument(0))));
        when(batches.findByStatus("OPEN")).thenAnswer(i -> new ArrayList<>(store.values().stream().filter(b -> "OPEN".equals(b.status)).toList()));
        when(batches.findByCallbackStatus(anyString())).thenReturn(List.of());
        when(batches.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(runs.findById(anyString())).thenAnswer(i -> Optional.ofNullable(runStore.get((String) i.getArgument(0))));
        service = new RegressionBatchService(batches, impact, planner, execution, defaults, runs, 2, 200, "http://localhost:8080");
    }

    private RegressionBatchService.StartRequest request(Map<String, Object> context, String callback) {
        return new RegressionBatchService.StartRequest(List.of(1L), "main", SHA_A, SHA_B, "test", 60L,
                context, Map.of(), "CI", "ci#1", null, callback);
    }

    private TestIR ir(String pointId, Map<String, Variable> initial) {
        return new TestIR("0.1", "flow." + pointId, "flow", 1, "h", pointId, "o", initial,
                List.of(), List.of(), List.of(), List.of(), List.of("http", "json"), null, List.of());
    }

    private void affected(String... points) {
        var list = Arrays.stream(points).map(p -> new BusinessTestImpactBridge.AffectedPoint("flow", 1, p, List.of("n1"), List.of(), List.of("d:e1"), "r")).toList();
        when(impact.analyze(anyMap())).thenReturn(new BusinessTestImpactBridge.Impact(Map.of("coverage", "STATIC_GRAPH"), list, List.of()));
    }

    private void scenario(String pointId, Map<String, Variable> initial) {
        when(planner.generate(eq("flow"), eq(1), eq(pointId), anyList())).thenReturn(ir(pointId, initial));
    }

    private int savedScenarios = 0;

    private void stubSaveAndDefaults(Map<String, Target> services, List<String> warnings) {
        when(execution.save(any())).thenAnswer(i -> { var s = new BusinessTestScenario(); s.id = "sc" + (++savedScenarios); return s; });
        when(defaults.resolve(any())).thenReturn(new ExecutionDefaultsService.Defaults(services, Map.of(), warnings));
    }

    private BusinessTestRun run(String id, String state, String verdict) {
        var r = new BusinessTestRun(); r.id = id; r.scenarioId = "x"; r.state = state; r.verdict = verdict; r.createdAt = Instant.now();
        runStore.put(id, r);
        return r;
    }

    @Test
    void aggregateFollowsSingleRunSemantics() {
        var pass = new RegressionBatchService.Item("f", 1, "p", List.of(), "s", "r", "DONE", "PASS", null);
        var fail = pass.with("s", "r", "DONE", "FAIL", null);
        var skipped = pass.with("s", null, "SKIPPED", null, "no url");
        assertEquals("NO_AFFECTED_TESTS", RegressionBatchService.aggregate(List.of()));
        assertEquals("PASS", RegressionBatchService.aggregate(List.of(pass, pass)));
        assertEquals("INCONCLUSIVE", RegressionBatchService.aggregate(List.of(pass, skipped)));
        assertEquals("FAIL", RegressionBatchService.aggregate(List.of(pass, skipped, fail)));
        var counts = RegressionBatchService.counts(List.of(pass, fail, skipped));
        assertEquals(3, counts.total());
        assertEquals(1, counts.pass());
        assertEquals(1, counts.fail());
        assertEquals(1, counts.skipped());
    }

    @Test
    void validatesRequestAndCallbackPrefix() {
        assertThrows(IllegalArgumentException.class, () -> service.start(new RegressionBatchService.StartRequest(
                List.of(1L), "main", "short", SHA_B, "test", 60L, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.start(request(Map.of(), "http://evil.example.com/hook")));
        assertDoesNotThrow(() -> service.start(request(Map.of(), "http://localhost:8080/api/change-review/regression-callback")));
    }

    @Test
    void endToEndHappyPathRespectsInFlightLimit() {
        affected("p1", "p2", "p3");
        for (String p : List.of("p1", "p2", "p3")) scenario(p, Map.of());
        stubSaveAndDefaults(Map.of("call:n1", new Target("http://svc")), List.of());
        int[] counter = {0};
        when(execution.run(anyString(), any(), anyLong(), anyString())).thenAnswer(i -> run("run" + (++counter[0]), "QUEUED", "INCONCLUSIVE"));

        var summary = service.start(request(Map.of(), null));
        service.tick();                       // prepare + submit 2 (limit)
        var after = service.get(summary.id());
        assertEquals(3, after.counts().total());
        assertEquals(2, after.counts().running());
        assertEquals(1, after.counts().pending());

        runStore.get("run1").state = "COMPLETED"; runStore.get("run1").verdict = "PASS";
        service.tick();                       // run1 done → submit third
        after = service.get(summary.id());
        assertEquals(2, after.counts().running());
        assertEquals(1, after.counts().pass());

        runStore.get("run2").state = "COMPLETED"; runStore.get("run2").verdict = "PASS";
        runStore.get("run3").state = "COMPLETED"; runStore.get("run3").verdict = "FAIL";
        service.tick();
        after = service.get(summary.id());
        assertEquals("COMPLETED", after.status());
        assertEquals("FAIL", after.verdict());
        verify(execution, times(3)).run(anyString(), any(), eq(60L), startsWith("batch:" + summary.id() + ":"));
    }

    @Test
    void missingInputsAreSkippedNotPassed() {
        affected("p1", "p2");
        scenario("p1", Map.of("tenant", new Variable("string", false)));
        scenario("p2", Map.of("token", new Variable("string", true)));
        stubSaveAndDefaults(Map.of(), List.of());

        var summary = service.start(request(Map.of(), null));
        service.tick();
        var after = service.get(summary.id());
        assertEquals("COMPLETED", after.status());
        assertEquals("INCONCLUSIVE", after.verdict());
        assertEquals(2, after.counts().skipped());
        assertTrue(after.items().get(0).reason().contains("tenant"));
        assertTrue(after.items().get(1).reason().contains("敏感"));
        verify(execution, never()).run(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void sensitiveValueSuppliedInRequestIsRejected() {
        affected("p1");
        scenario("p1", Map.of("token", new Variable("string", true)));
        stubSaveAndDefaults(Map.of(), List.of());
        var summary = service.start(request(Map.of("token", "should-not-be-used"), null));
        service.tick();
        assertEquals("SKIPPED", service.get(summary.id()).items().get(0).state());
    }

    @Test
    void queueFullKeepsItemPending() {
        affected("p1");
        scenario("p1", Map.of());
        stubSaveAndDefaults(Map.of("call:n1", new Target("http://svc")), List.of());
        when(execution.run(anyString(), any(), anyLong(), anyString())).thenAnswer(i -> {
            var r = run("q1", "FAILED", "INCONCLUSIVE"); r.error = "Execution queue full"; return r;
        });
        var summary = service.start(request(Map.of(), null));
        service.tick();
        assertEquals(1, service.get(summary.id()).counts().pending());
    }

    @Test
    void impactFailureFailsBatch() {
        when(impact.analyze(anyMap())).thenThrow(new IllegalStateException("query service down"));
        var summary = service.start(request(Map.of(), null));
        service.tick();
        var after = service.get(summary.id());
        assertEquals("FAILED", after.status());
        assertEquals("INCONCLUSIVE", after.verdict());
        assertTrue(after.error().contains("影响分析失败"));
    }

    @Test
    void noAffectedTestsCompletesImmediately() {
        affected();
        var summary = service.start(request(Map.of(), "http://localhost:8080/cb"));
        service.tick();
        var after = service.get(summary.id());
        assertEquals("NO_AFFECTED_TESTS", after.verdict());
        assertEquals("PENDING", after.callbackStatus());
    }

    @Test
    void cancelMarksOutstandingItems() {
        affected("p1", "p2", "p3");
        for (String p : List.of("p1", "p2", "p3")) scenario(p, Map.of());
        stubSaveAndDefaults(Map.of("call:n1", new Target("http://svc")), List.of());
        int[] counter = {0};
        when(execution.run(anyString(), any(), anyLong(), anyString())).thenAnswer(i -> run("c" + (++counter[0]), "QUEUED", "INCONCLUSIVE"));
        var summary = service.start(request(Map.of(), null));
        service.tick();
        var cancelled = service.cancel(summary.id());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(0, cancelled.counts().pending() + cancelled.counts().running());
        verify(execution, times(2)).cancel(anyString());
    }
}
