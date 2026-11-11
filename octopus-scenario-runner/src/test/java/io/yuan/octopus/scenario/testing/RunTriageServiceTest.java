package io.yuan.octopus.scenario.testing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static io.yuan.octopus.scenario.testing.TestingJson.canonical;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Phase 1D：失败分析只作建议、输入最小化、输出严格校验。 */
class RunTriageServiceTest {

    private static TestIR ir() {
        var op = new Operation("create", "v1", "shop", "order", "POST", "/orders",
                Map.of("name", new Port("body", "name", "string", true, null)), Map.of(), Map.of(), List.of(),
                new Source("openapi", "x", "d"), List.of(), null);
        var step = new Step("create", "create@v1", Map.of("name", new Binding("literal", "SECRET-LITERAL")),
                List.of(new Output("order.id", "$.id", "string", false)), List.of(),
                List.of(new Assertion("st", "STATUS_EQUALS", null, 201), new Assertion("ok", "JSON_EQUALS", "$.status", "NEW")), 0);
        return new TestIR("0.1", "f.tp", "f", 1, "h", "tp", "o", Map.of(), List.of(op), List.of(), List.of(step), List.of(),
                List.of("http", "json"), null, List.of());
    }

    @Test
    void stepViewNeverIncludesLiteralInputValues() {
        String json = canonical(RunTriageService.steps(ir()));
        assertFalse(json.contains("SECRET-LITERAL"));
        assertTrue(json.contains("\"name\":\"literal\""));
        assertTrue(json.contains("\"ok\""));
    }

    @Test
    void parsesValidOutputIncludingFences() {
        String content = "```json\n{\"category\":\"TEST_DATA\",\"confidence\":\"MEDIUM\",\"summary\":\"订单前置数据缺失\","
                + "\"failedChecks\":[{\"stepId\":\"create\",\"assertionId\":\"ok\",\"explanation\":\"状态不是 NEW\"}],"
                + "\"evidence\":[\"step:create → 409\"],\"suggestedActions\":[\"检查测试数据\"],\"questions\":[]}\n```";
        var triage = RunTriageService.parse(content, ir());
        assertEquals("TEST_DATA", triage.category());
        assertEquals(1, triage.failedChecks().size());
    }

    @Test
    void rejectsInventedStepsAssertionsAndCategories() {
        String base = "{\"category\":\"%s\",\"confidence\":\"LOW\",\"summary\":\"s\",\"failedChecks\":[{\"stepId\":\"%s\",\"assertionId\":%s,\"explanation\":\"e\"}],\"evidence\":[],\"suggestedActions\":[],\"questions\":[]}";
        assertThrows(IllegalArgumentException.class, () -> RunTriageService.parse(base.formatted("MAGIC", "create", "null"), ir()));
        assertThrows(IllegalArgumentException.class, () -> RunTriageService.parse(base.formatted("UNKNOWN", "ghost", "null"), ir()));
        assertThrows(IllegalArgumentException.class, () -> RunTriageService.parse(base.formatted("UNKNOWN", "create", "\"nope\""), ir()));
        assertThrows(IllegalArgumentException.class, () -> RunTriageService.parse("{\"category\":\"UNKNOWN\",\"confidence\":\"LOW\",\"summary\":\"s\",\"extra\":1}", ir()));
        assertThrows(IllegalArgumentException.class, () -> RunTriageService.parse("not json", ir()));
        assertDoesNotThrow(() -> RunTriageService.parse(base.formatted("UNKNOWN", "create", "null"), ir()));
    }

    @Test
    void deterministicSignals() {
        var run = new BusinessTestRun();
        run.id = "r"; run.state = "COMPLETED"; run.verdict = "FAIL"; run.createdAt = Instant.now();
        var report = new JmeterProcessExecutor.Result("COMPLETED", "FAIL", List.of(
                new JmeterProcessExecutor.Sample("step:a", false, "Non HTTP response code: java.net.ConnectException", 5, "x"),
                new JmeterProcessExecutor.Sample("step:b", false, "503", 5, "x"),
                new JmeterProcessExecutor.Sample("step:c", false, "200", 5, "Business verification failed: s | schema: s $.id: expected string")),
                null, true, "FAIL", false, List.of(new JmeterProcessExecutor.CallResult("d", "BLOCKED", false)));
        var signals = RunTriageService.signals(run, report);
        assertTrue(signals.stream().anyMatch(s -> s.startsWith("NON_HTTP_STATUS: step:a")));
        assertTrue(signals.stream().anyMatch(s -> s.startsWith("SERVER_ERROR: step:b")));
        assertTrue(signals.stream().anyMatch(s -> s.startsWith("SCHEMA_VIOLATION: step:c")));
        assertTrue(signals.contains("BLOCKED: d"));
        assertTrue(signals.stream().anyMatch(s -> s.startsWith("CLEANUP_FAILED")));
    }

    @Test
    void triageNeverChangesTheRunVerdictAndOnlyAnalyzesFailures() {
        var runs = mock(BusinessTestRunRepository.class);
        var scenarios = mock(BusinessTestScenarioRepository.class);
        var models = mock(FlowModelClient.class);
        var triages = mock(RunTriageRepository.class);
        var metrics = mock(SuggestionMetricsService.class);
        var service = new RunTriageService(runs, scenarios, mock(BusinessKnowledgeStore.class), models, triages, metrics);

        var passed = new BusinessTestRun(); passed.id = "p"; passed.state = "COMPLETED"; passed.verdict = "PASS"; passed.scenarioId = "s";
        when(runs.findById("p")).thenReturn(Optional.of(passed));
        assertThrows(IllegalArgumentException.class, () -> service.triage("p", null));

        var failed = new BusinessTestRun(); failed.id = "f"; failed.state = "COMPLETED"; failed.verdict = "FAIL"; failed.scenarioId = "s";
        when(runs.findById("f")).thenReturn(Optional.of(failed));
        var scenario = new BusinessTestScenario(); scenario.id = "s"; scenario.irJson = canonical(ir());
        when(scenarios.findById("s")).thenReturn(Optional.of(scenario));
        when(triages.findById("f")).thenReturn(Optional.empty());
        when(models.runTriage(any(), anyMap())).thenReturn(new FlowModelClient.Response(
                "{\"category\":\"PRODUCT_DEFECT\",\"confidence\":\"HIGH\",\"summary\":\"s\",\"failedChecks\":[],\"evidence\":[],\"suggestedActions\":[],\"questions\":[]}", "m1"));

        var view = service.triage("f", null);

        assertEquals("PRODUCT_DEFECT", view.triage().category());
        assertTrue(view.advisory());
        assertEquals("FAIL", failed.verdict);
        verify(runs, never()).save(any());
        verify(triages).save(argThat(t -> "f".equals(t.runId) && "PRODUCT_DEFECT".equals(t.category)));
        verify(metrics).record(eq("RUN_TRIAGE"), eq("GENERATED"), eq("m1"), eq("f"), anyLong(), anyInt(), eq(0), isNull());
    }
}
