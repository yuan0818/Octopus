package io.yuan.octopus.scenario.testing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunHistoryServiceTest {

    private static RunHistoryService.Entry e(String state, String verdict) {
        return new RunHistoryService.Entry("r", "s", 1, state, verdict, null, null, null);
    }

    @Test
    void flakyNeedsBothOutcomesAndRepeatedFlips() {
        assertTrue(RunHistoryService.flakiness(List.of(e("COMPLETED", "PASS"), e("COMPLETED", "FAIL"), e("COMPLETED", "PASS"))).flaky());
        assertFalse(RunHistoryService.flakiness(List.of(e("COMPLETED", "FAIL"), e("COMPLETED", "PASS"), e("COMPLETED", "PASS"))).flaky(),
                "a single flip is a regression or a fix, not flakiness");
        assertFalse(RunHistoryService.flakiness(List.of(e("COMPLETED", "FAIL"), e("COMPLETED", "FAIL"))).flaky());
        var withNoise = RunHistoryService.flakiness(List.of(e("COMPLETED", "PASS"), e("TIMED_OUT", "INCONCLUSIVE"), e("COMPLETED", "FAIL"),
                e("RUNNING", "INCONCLUSIVE"), e("COMPLETED", "PASS")));
        assertTrue(withNoise.flaky());
        assertEquals(1, withNoise.inconclusive());
        assertEquals(4, withNoise.window());
    }

    @Test
    void windowIsLimitedToRecentRuns() {
        List<RunHistoryService.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) entries.add(e("COMPLETED", "PASS"));
        entries.add(e("COMPLETED", "FAIL"));
        entries.add(e("COMPLETED", "PASS"));
        assertFalse(RunHistoryService.flakiness(entries).flaky());
    }

    @Test
    void historyGroupsByRevision() {
        var scenarios = mock(BusinessTestScenarioRepository.class);
        var runs = mock(BusinessTestRunRepository.class);
        var s1 = new BusinessTestScenario(); s1.id = "s1"; s1.flowId = "f"; s1.flowRevision = 1; s1.testPointId = "tp";
        var s2 = new BusinessTestScenario(); s2.id = "s2"; s2.flowId = "f"; s2.flowRevision = 2; s2.testPointId = "tp";
        when(scenarios.findTop500ByFlowIdAndTestPointIdOrderByCreatedAtDesc("f", "tp")).thenReturn(List.of(s2, s1));
        List<BusinessTestRun> list = new ArrayList<>();
        for (String[] r : new String[][]{{"a", "s2", "PASS"}, {"b", "s1", "FAIL"}, {"c", "s1", "PASS"}, {"d", "s1", "FAIL"}}) {
            var run = new BusinessTestRun(); run.id = r[0]; run.scenarioId = r[1]; run.state = "COMPLETED"; run.verdict = r[2]; run.createdAt = Instant.now();
            list.add(run);
        }
        when(runs.findTop200ByScenarioIdInOrderByCreatedAtDesc(anyCollection())).thenReturn(list);
        var history = new RunHistoryService(scenarios, runs).history("f", "tp", 50);
        assertEquals(4, history.runs().size());
        assertTrue(history.byRevision().get(1).flaky());
        assertFalse(history.byRevision().get(2).flaky());
    }
}
