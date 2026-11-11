package io.yuan.octopus.scenario.testing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Phase 2.4：数据行校验、逐行提交（并发上限）、汇总判定。 */
class DataRunServiceTest {

    private DataRunRepository repo;
    private BusinessTestExecutionService execution;
    private BusinessTestRunRepository runs;
    private DataRunService service;
    private final Map<String, DataRun> store = new HashMap<>();
    private final Map<String, BusinessTestRun> runStore = new HashMap<>();

    private static TestIR ir(Map<String, Variable> initial) {
        return new TestIR("0.1", "f.tp", "f", 1, "h", "tp", "o", initial, List.of(), List.of(), List.of(), List.of(),
                List.of("http", "json"), null, List.of());
    }

    @BeforeEach
    void setUp() {
        repo = mock(DataRunRepository.class);
        execution = mock(BusinessTestExecutionService.class);
        runs = mock(BusinessTestRunRepository.class);
        when(repo.save(any())).thenAnswer(i -> { DataRun r = i.getArgument(0); store.put(r.id, r); return r; });
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(store.get((String) i.getArgument(0))));
        when(repo.findByStatus("OPEN")).thenAnswer(i -> store.values().stream().filter(r -> "OPEN".equals(r.status)).toList());
        when(runs.findById(anyString())).thenAnswer(i -> Optional.ofNullable(runStore.get((String) i.getArgument(0))));
        service = new DataRunService(repo, execution, runs, 2);
    }

    private DataRunService.StartRequest request(List<DataRunService.RowInput> rows) {
        return new DataRunService.StartRequest("sc", Map.of("shop.order", new Target("http://svc")), "test", Map.of(),
                Map.of("tenant", "qa"), rows, 60L);
    }

    @Test
    void validatesRowsAgainstDeclaredVariables() {
        when(execution.load("sc")).thenReturn(ir(Map.of("tenant", new Variable("string", false), "qty", new Variable("integer", false))));
        assertThrows(IllegalArgumentException.class, () -> service.start(request(List.of(new DataRunService.RowInput("a", Map.of("qty", "x"))))));
        assertThrows(IllegalArgumentException.class, () -> service.start(request(List.of(new DataRunService.RowInput("a", Map.of("unknown", 1))))));
        assertThrows(IllegalArgumentException.class, () -> service.start(request(List.of(new DataRunService.RowInput("a", Map.of())))),
                "qty is missing in base and row");
        assertThrows(IllegalArgumentException.class, () -> service.start(request(List.of(
                new DataRunService.RowInput("dup", Map.of("qty", 1)), new DataRunService.RowInput("dup", Map.of("qty", 2))))));
        assertDoesNotThrow(() -> service.start(request(List.of(new DataRunService.RowInput("a", Map.of("qty", 1))))));
    }

    @Test
    void rejectsScenariosWithSensitiveInitialVariables() {
        when(execution.load("sc")).thenReturn(ir(Map.of("tenant", new Variable("string", false), "token", new Variable("string", true))));
        assertThrows(IllegalArgumentException.class, () -> service.start(request(List.of(new DataRunService.RowInput("a", Map.of())))));
    }

    @Test
    void runsRowsWithInFlightLimitAndAggregates() {
        when(execution.load("sc")).thenReturn(ir(Map.of("tenant", new Variable("string", false), "qty", new Variable("integer", false))));
        int[] n = {0};
        List<ExecutionProfile> profiles = new ArrayList<>();
        when(execution.run(eq("sc"), any(), eq(60L), anyString())).thenAnswer(i -> {
            profiles.add(i.getArgument(1));
            var r = new BusinessTestRun(); r.id = "r" + (++n[0]); r.state = "QUEUED"; r.verdict = "INCONCLUSIVE"; r.createdAt = Instant.now();
            runStore.put(r.id, r); return r;
        });
        var started = service.start(request(List.of(new DataRunService.RowInput("one", Map.of("qty", 1)),
                new DataRunService.RowInput("two", Map.of("qty", 2)), new DataRunService.RowInput("three", Map.of("qty", 3, "tenant", "other")))));

        service.tick();
        assertEquals(2, profiles.size());
        assertEquals("qa", profiles.get(0).initialContext().get("tenant"));
        runStore.get("r1").state = "COMPLETED"; runStore.get("r1").verdict = "PASS";
        service.tick();
        assertEquals(3, profiles.size());
        assertEquals("other", profiles.get(2).initialContext().get("tenant"), "row values override base values");
        runStore.get("r2").state = "COMPLETED"; runStore.get("r2").verdict = "FAIL";
        runStore.get("r3").state = "COMPLETED"; runStore.get("r3").verdict = "PASS";
        service.tick();
        var done = service.get(started.id());
        assertEquals("COMPLETED", done.status());
        assertEquals("FAIL", done.verdict());
        assertEquals(Map.of("FAIL", 1, "PASS", 2), done.counts());
    }
}
