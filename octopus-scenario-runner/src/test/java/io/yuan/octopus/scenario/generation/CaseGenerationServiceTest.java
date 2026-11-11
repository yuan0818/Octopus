package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.yuan.octopus.scenario.testing.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 候选视图、分组与应用规则（OBS-002、OBS-004、AT-G20、TRACE-003/005）。 */
class CaseGenerationServiceTest {
    final Flow flow = GenerationFixtures.flow(false);
    final BusinessKnowledgeStore knowledge = GenerationFixtures.store(flow);
    final FlowModelClient models = mock(FlowModelClient.class);
    final CaseGenerationEngineTest.FakeGateway gateway = new CaseGenerationEngineTest.FakeGateway();

    CaseGenerationEngine.Task finished() {
        when(models.casePlan(any(), any(), any())).thenReturn(new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "v"));
        // 基线：一致；模型负向意图：实际 200 而非 422 → 矛盾
        gateway.behaviour = s -> s.ir().steps().size() == 2 && s.ir().intentionallyViolatedRule() == null && s.ir().steps().getLast().inputs().get("quantity").value() instanceof Number n && n.intValue() == 0
                ? CaseGenerationEngineTest.result("COMPLETED", List.of(CaseGenerationEngineTest.sample("step:login", true, "200", ""),
                        CaseGenerationEngineTest.sample("step:create", false, "200", "Business verification failed: neg_status, neg_1")))
                : CaseGenerationEngineTest.result("COMPLETED", List.of(CaseGenerationEngineTest.sample("step:login", true, "200", ""), CaseGenerationEngineTest.sample("step:create", true, "201", "")));
        var engine = new CaseGenerationEngine(knowledge, new ModelIntentSource(models), new DataSlotFiller(models, null), checks(), gateway,
                CaseGenerationEngine.Budget.DEFAULT, java.time.Clock.systemUTC());
        engine.pollMillis = 1;
        var t = new CaseGenerationEngine.Task();
        t.id = "task-apply";
        t.request = GenerationFixtures.request(List.of(Strategy.RULE, Strategy.MODEL), ProbeMode.ON, null);
        t.createdAt = java.time.Instant.now().toString();
        int guard = 0;
        while (engine.advance(t, () -> false)) { t = CaseGenerationEngineTest.checkpoint(t); assertTrue(++guard < 500); }
        return CaseGenerationEngineTest.checkpoint(t);
    }

    StaticChecks checks() {
        return new StaticChecks(GenerationFixtures.planner(knowledge), knowledge) {
            @Override public Checked check(FlowRevision confirmed, TestPoint candidate, List<String> issues, ProbeConfig probe) {
                var c = super.check(confirmed, candidate, issues, null);
                return new Checked(c.result(), c.ir(), c.result().ok() && probe != null && probe.mode() == ProbeMode.ON ? new ExecutionProfile(Map.of(), Map.of(), Map.of(), "gen-sandbox") : null);
            }
        };
    }

    CaseGenerationService service(CaseGenerationEngine.Task task) {
        var store = mock(CaseGenerationStore.class);
        when(store.get(task.id)).thenReturn(task);
        return new CaseGenerationService(store, knowledge, null, null, mock(ProbeExecutionService.class), CaseGenerationEngine.Budget.DEFAULT);
    }

    @Test
    void viewGroupsCandidatesAndSummarisesTheRun() {
        var task = finished();
        var view = service(task).view(task);
        var baseline = view.candidates().stream().filter(c -> "BASELINE".equals(c.category())).findFirst().orElseThrow();
        assertEquals("READY", baseline.group());
        assertEquals("CONSISTENT", baseline.conclusion());
        assertEquals(CandidateStatusRule.READY, baseline.status(), "AT-G20: consistent candidates still need confirmation");
        var negative = view.candidates().stream().filter(c -> "MODEL".equals(c.source())).findFirst().orElseThrow();
        assertEquals("CONTRADICTED", negative.group());
        assertEquals(1, view.summary().suspectedDefects());
        assertEquals("NEEDS_INPUT", view.state());
        assertTrue(view.roleCalls().stream().map(CaseGenerationEngine.RoleCall::role).toList().containsAll(List.of("PLANNER", "OBSERVER")));
        assertTrue(view.roleCalls().stream().anyMatch(r -> "case-plan@1".equals(r.promptVersion()) || "v".equals(r.promptVersion())), "TRACE-002 prompt version");
    }

    @Test
    void applyRequiresADecisionForContradictionsAndTagsDefects() {
        var task = finished();
        var service = service(task);
        var view = service.view(task);
        String baseline = view.candidates().stream().filter(c -> "BASELINE".equals(c.category())).findFirst().orElseThrow().intentId();
        String negative = view.candidates().stream().filter(c -> "MODEL".equals(c.source())).findFirst().orElseThrow().intentId();

        var undecided = service.apply(task.id, new CaseGenerationService.ApplyRequest(List.of(baseline, negative), Map.of(), null));
        assertEquals(1, undecided.points().size());
        assertTrue(undecided.skipped().getFirst().reason().contains("请先判断"));

        var wrong = service.apply(task.id, new CaseGenerationService.ApplyRequest(List.of(negative), Map.of(negative, "SOURCE_WRONG"), null));
        assertTrue(wrong.points().isEmpty());

        var defect = service.apply(task.id, new CaseGenerationService.ApplyRequest(List.of(negative), Map.of(negative, "DEFECT"), null));
        var point = defect.points().getFirst();
        assertTrue(point.generatedFrom().flags().contains("SUSPECTED_DEFECT"));
        assertEquals(task.id, point.generatedFrom().taskId());
        assertEquals(negative, point.generatedFrom().intentId());
        assertFalse(point.generatedFrom().observationIds().isEmpty());
        assertEquals(422, ((Number) point.assertions().get("create").getFirst().expected()).intValue(), "the original expectation is kept");
    }

    @Test
    void humanExpectationSourcesMayOnlyCiteObservationsOfThisTask() {
        // OBS-004
        var task = finished();
        var service = service(task);
        String baseline = service.view(task).candidates().stream().filter(c -> "BASELINE".equals(c.category())).findFirst().orElseThrow().intentId();
        String observation = CaseGenerationService.observationId(task.current().observations.get(baseline));
        var ok = service.apply(task.id, new CaseGenerationService.ApplyRequest(List.of(baseline), Map.of(),
                Map.of(baseline, Map.of("create/extra", new ExpectationSource("HUMAN", null, observation)))));
        assertEquals("HUMAN", ok.points().getFirst().generatedFrom().expectationSources().get("create/extra").type());
        assertThrows(IllegalArgumentException.class, () -> service.apply(task.id, new CaseGenerationService.ApplyRequest(List.of(baseline), Map.of(),
                Map.of(baseline, Map.of("create/extra", new ExpectationSource("HUMAN", null, "probe:someone-else"))))));
        assertThrows(IllegalArgumentException.class, () -> service.apply(task.id, new CaseGenerationService.ApplyRequest(List.of(baseline), Map.of(),
                Map.of(baseline, Map.of("create/extra", new ExpectationSource("CONTRACT", "x", null))))));
    }

    @Test
    void sideEffectUnknownCandidatesCannotBeApplied() {
        var task = finished();
        var round = task.current();
        String baseline = round.plan.intents().stream().filter(TestIntent::baseline).findFirst().orElseThrow().intentId();
        var o = round.observations.get(baseline);
        round.observations.put(baseline, new Observation(o.intentId(), o.round(), o.source(), o.probeRunId(), Conclusion.SIDE_EFFECT_UNKNOWN,
                o.assertions(), o.calls(), o.feedback(), o.evidence(), "UNKNOWN"));
        var result = service(task).apply(task.id, new CaseGenerationService.ApplyRequest(List.of(baseline), Map.of(), null));
        assertTrue(result.points().isEmpty());
        assertTrue(result.skipped().getFirst().reason().contains("副作用未知"));
    }

    @Test
    void legacyRuleEndpointRunsThroughTheThreeRoles() {
        var legacy = new CaseCandidateService(knowledge);
        var result = legacy.generate(new CaseCandidateService.Request("orders", 1, "happy", 100, 422, "错误码规范"));
        assertTrue(result.candidates().stream().allMatch(c -> c.point().generatedFrom().intentId() != null), "every candidate came from a Planner intent");
        assertTrue(result.candidates().stream().filter(c -> c.sourceRule().endsWith("Outside")).allMatch(c -> "BLOCKED".equals(c.status())));
        assertEquals("CONFIRMED_BASELINE", result.candidates().getFirst().sourceRule());
        assertEquals("NEEDS_REVIEW", result.candidates().getFirst().status());
        verify(knowledge, never()).saveDraft(any());
    }
}
