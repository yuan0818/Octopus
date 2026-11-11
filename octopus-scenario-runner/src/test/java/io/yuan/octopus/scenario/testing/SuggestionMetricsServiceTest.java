package io.yuan.octopus.scenario.testing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SuggestionMetricsServiceTest {

    private static SuggestionEvent event(String kind, String outcome, String model, String ref, Long latency, String code) {
        var e = new SuggestionEvent();
        e.id = UUID.randomUUID().toString(); e.kind = kind; e.outcome = outcome; e.model = model; e.refId = ref;
        e.latencyMs = latency; e.failureCode = code; e.createdAt = Instant.now();
        return e;
    }

    @Test
    void summarizesRatesLatencyAndDedupesReplayedConfirmations() {
        var events = List.of(
                event("STEP", "GENERATED", "m1", null, 100L, null),
                event("STEP", "GENERATED", "m1", null, 300L, null),
                event("STEP", "GENERATED", "m1", null, 200L, null),
                event("STEP", "FAILED", "m1", null, 50L, "MODEL_TIMEOUT"),
                event("STEP", "ACCEPTED", "m1", "mem1", null, null),
                event("STEP", "ACCEPTED", "m1", "mem1", null, null),   // replay
                event("STEP", "REJECTED", "m1", "mem2", null, null),
                event("RUN_TRIAGE", "GENERATED", "m2", "run1", 900L, null));
        var summary = SuggestionMetricsService.summarize(events, Instant.EPOCH);
        assertEquals(8, summary.events());
        var step = summary.rows().stream().filter(r -> r.kind().equals("STEP")).findFirst().orElseThrow();
        assertEquals(3, step.generated());
        assertEquals(1, step.failed());
        assertEquals(1, step.accepted());
        assertEquals(1, step.rejected());
        assertEquals(0.25, step.failureRate());
        assertEquals(0.5, step.acceptanceRate());
        assertEquals(100L, step.p50LatencyMs());
        assertEquals(300L, step.p95LatencyMs());
        assertEquals(Map.of("MODEL_TIMEOUT", 1L), step.failureCodes());
        assertEquals(2, summary.rows().size());
    }

    @Test
    void percentileEdgeCases() {
        assertNull(SuggestionMetricsService.percentile(List.of(), 0.5));
        assertEquals(7L, SuggestionMetricsService.percentile(List.of(7L), 0.95));
        assertEquals(2L, SuggestionMetricsService.percentile(List.of(1L, 2L, 3L, 4L), 0.5));
    }

    private static Flow flow(List<Output> outputs, Map<String, Binding> inputs, List<Assertion> assertions) {
        var node = new FlowNode("n1", "create", "op@v1", inputs, outputs, List.of(), 0);
        var point = new TestPoint("tp", "normal", List.of("n1"), Map.of("n1", assertions), Map.of(), null);
        return new Flow("f", 1, "flow", Map.of(), List.of(node), List.of(), List.of(), List.of(point));
    }

    @Test
    void goldenEvaluationIgnoresNamingAndBodyPrefix() {
        var golden = flow(List.of(new Output("order.id", "$.id", "string", false)),
                Map.of("user", new Binding("context", "test.user")),
                List.of(new Assertion("st", "STATUS_EQUALS", null, 201), new Assertion("a1", "JSON_EQUALS", "$.status", "NEW")));
        var candidate = flow(List.of(new Output("created.order", "$.body.id", "string", false)),
                Map.of("user", new Binding("context", "test.user"), "note", new Binding("literal", "x")),
                List.of(new Assertion("status", "STATUS_EQUALS", null, 201)));
        var service = new SuggestionMetricsService(mock(SuggestionEventRepository.class));
        var result = service.evaluate(golden, candidate);
        assertEquals(1.0, result.outputs().f1());
        assertEquals(1, result.inputs().matched());
        assertEquals(0.5, result.inputs().precision());
        assertEquals(1.0, result.inputs().recall());
        assertEquals(1.0, result.assertions().precision());
        assertEquals(0.5, result.assertions().recall());
        assertEquals(1, result.assertions().missing().size());
        assertTrue(result.overallF1() > 0.7 && result.overallF1() < 1.0);
    }

    @Test
    void feedbackValidation() {
        var repo = mock(SuggestionEventRepository.class);
        var service = new SuggestionMetricsService(repo);
        assertThrows(IllegalArgumentException.class, () -> service.feedback(new SuggestionMetricsService.Feedback("step", "ACCEPTED", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.feedback(new SuggestionMetricsService.Feedback("STEP", "GENERATED", null, null, null)));
        service.feedback(new SuggestionMetricsService.Feedback("VERIFICATION", "EDITED", "plan-1", "m1", 3));
        verify(repo).save(argThat(e -> "EDITED".equals(e.outcome) && Integer.valueOf(3).equals(e.changedItems)));
    }
}
