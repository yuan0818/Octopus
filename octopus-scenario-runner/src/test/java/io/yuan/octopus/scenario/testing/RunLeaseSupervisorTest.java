package io.yuan.octopus.scenario.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Phase 2.2：只回收心跳过期且不属于本进程的运行；QUEUED 与 RUNNING 给出不同的处理建议；清理凭据临时文件。 */
class RunLeaseSupervisorTest {

    @TempDir Path artifacts;

    private static BusinessTestRun run(String id, String state, Instant heartbeat) {
        var r = new BusinessTestRun();
        r.id = id; r.scenarioId = "s"; r.state = state; r.verdict = "INCONCLUSIVE"; r.createdAt = heartbeat; r.heartbeatAt = heartbeat;
        return r;
    }

    @Test
    void recoversOnlyExpiredForeignRuns() throws Exception {
        var runs = mock(BusinessTestRunRepository.class);
        var execution = mock(BusinessTestExecutionService.class);
        Instant now = Instant.now();
        var staleQueued = run("q", "QUEUED", now.minusSeconds(600));
        var staleRunning = run("r", "RUNNING", now.minusSeconds(600));
        var fresh = run("f", "RUNNING", now.minusSeconds(5));
        var mine = run("m", "RUNNING", now.minusSeconds(600));
        when(runs.findByStateIn(any())).thenReturn(List.of(staleQueued, staleRunning, fresh, mine));
        when(execution.activeRunIds()).thenReturn(Set.of("m"));
        when(execution.artifactDirectory(any())).thenAnswer(i -> artifacts.resolve((String) i.getArgument(0)));
        Files.createDirectories(artifacts.resolve("r"));
        Files.writeString(artifacts.resolve("r/context.json"), "{\"token\":\"secret\"}");
        Files.writeString(artifacts.resolve("r/results.jtl"), "keep");

        int recovered = new RunLeaseSupervisor(runs, execution, 15, 120).recover(now);

        assertEquals(2, recovered);
        assertEquals("INTERRUPTED", staleQueued.state);
        assertEquals(RunLeaseSupervisor.QUEUED_REASON, staleQueued.error);
        assertEquals("INTERRUPTED", staleRunning.state);
        assertEquals(RunLeaseSupervisor.RUNNING_REASON, staleRunning.error);
        assertEquals("INCONCLUSIVE", staleRunning.verdict);
        assertEquals("RUNNING", fresh.state);
        assertEquals("RUNNING", mine.state);
        assertFalse(Files.exists(artifacts.resolve("r/context.json")), "secrets must be scrubbed");
        assertTrue(Files.exists(artifacts.resolve("r/results.jtl")), "evidence is kept");
        verify(runs, times(2)).save(any());
    }

    @Test
    void heartbeatTouchesOnlyActiveRuns() {
        var runs = mock(BusinessTestRunRepository.class);
        var execution = mock(BusinessTestExecutionService.class);
        when(execution.activeRunIds()).thenReturn(Set.of());
        var supervisor = new RunLeaseSupervisor(runs, execution, 15, 120);
        supervisor.heartbeatSafely();
        verify(runs, never()).heartbeat(any(), any());
        when(execution.activeRunIds()).thenReturn(Set.of("a"));
        supervisor.heartbeatSafely();
        verify(runs).heartbeat(eq(Set.of("a")), any());
    }
}
