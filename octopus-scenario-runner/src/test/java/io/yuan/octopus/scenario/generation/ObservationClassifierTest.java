package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;

import io.yuan.octopus.scenario.testing.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** OBS-001～007 与设计 5.4：确定性分类（AT-G03～G07）。 */
class ObservationClassifierTest {
    final Flow flow = GenerationFixtures.flow(false);
    final BusinessKnowledgeStore store = GenerationFixtures.store(flow);
    final StaticChecks checks = new StaticChecks(GenerationFixtures.planner(store), store);
    final Map<String, Operation> ops = GenerationFixtures.operations();

    record Built(TestIntent intent, CandidateDraft draft, TestIR ir) {}

    Built baseline() {
        var intent = RuleIntentSource.plan(flow, GenerationFixtures.happy(), ops, null, Set.of()).intents().getFirst();
        return build(intent);
    }

    Built negative(String sideEffectKind) {
        List<ModelIntentSource.ModelAssertion> assertions = new ArrayList<>(List.of(new ModelIntentSource.ModelAssertion("JSON_EQUALS", "$.code", "QUANTITY_INVALID")));
        var plan = new ModelIntentSource.PlanIntent(null, null, "数量 0", "NEGATIVE", "create", Map.of("quantity", 0), null, null, 422,
                assertions, null, "r", List.of(), null);
        var intent = ModelIntentSource.toIntent(plan, flow, GenerationFixtures.happy(), OrderedFlows.prefix(flow, GenerationFixtures.happy()), ops, null);
        intent = intent.withExpectations(PlanGuard.checkSources(intent, flow, ops, null), intent.issues());
        if (sideEffectKind != null) {
            var list = new ArrayList<>(intent.expectations());
            var key = new io.yuan.octopus.verification.VerificationPlan.Value("context", "auth.token");
            var check = new io.yuan.octopus.verification.VerificationPlan("SQL", new io.yuan.octopus.verification.VerificationPlan.Source("repo", "module", "connection"), "无订单写入",
                    new io.yuan.octopus.verification.VerificationPlan.Sql("orders", List.of("status"), false, List.of(new io.yuan.octopus.verification.VerificationPlan.Filter("owner", "EQ", key))),
                    null, null, new io.yuan.octopus.verification.VerificationPlan.Expectation("$.rows[0].status", "EQ", new io.yuan.octopus.verification.VerificationPlan.Value("literal", "NONE")), 1000);
            list.add(new Expectation("create", new Assertion("no_order", "DATASTORE_VERIFY", null, check), SourceType.HUMAN, "point/happy/no_order", null, null));
            intent = intent.withExpectations(list, intent.issues());
        }
        return build(intent.withId(PlanGuard.intentId(intent)));
    }

    Built build(TestIntent intent) {
        var m = IntentMaterializer.materialize(intent, "hash", "happy", "task", 1, "m", Map.of(), Set.of());
        var checked = checks.check(GenerationFixtures.confirmed(flow), m.point(), m.issues(), null);
        var draft = new CandidateDraft(intent.intentId(), 1, m.point(), m.fingerprint(), checked.result(), true, null, m.issues());
        assertTrue(checked.result().ok(), String.valueOf(checked.result().errors()));
        return new Built(intent, draft, checked.ir());
    }

    static JmeterProcessExecutor.Sample ok(String label, String code) { return new JmeterProcessExecutor.Sample(label, true, code, 5, ""); }
    static JmeterProcessExecutor.Sample failed(String label, String code, String failure) { return new JmeterProcessExecutor.Sample(label, false, code, 5, failure); }

    static ObservationClassifier.ProbeOutcome outcome(String state, String cleanup, List<JmeterProcessExecutor.Sample> samples, Map<String, String> calls) {
        List<JmeterProcessExecutor.CallResult> results = new ArrayList<>();
        calls.forEach((id, status) -> results.add(new JmeterProcessExecutor.CallResult(id, status, false)));
        var result = new JmeterProcessExecutor.Result(state, "INCONCLUSIVE", samples, null, "COMPLETED".equals(state), cleanup, false, results);
        return new ObservationClassifier.ProbeOutcome("run-1", state, true, result, List.of());
    }

    @Test
    void consistentWhenAllExpectationsMatch() {
        var b = baseline();
        var o = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), outcome("COMPLETED", "NOT_REQUIRED",
                List.of(ok("guard:login", "200"), ok("step:login", "200"), ok("guard:create", "200"), ok("step:create", "201")),
                Map.of("login", "PASS", "create", "PASS")), 1);
        assertEquals(Conclusion.CONSISTENT, o.conclusion());
        assertTrue(o.assertions().stream().allMatch(a -> a.classification() == AssertionClass.MATCHED));
        assertEquals(CandidateStatusRule.READY, CandidateStatusRule.of(b.intent(), b.draft(), o).status(), "AT-G20: consistent is still only ready for review");
    }

    @Test
    void contractMismatchIsContradictedAndRoutedToHumans() {
        // AT-G03：契约 201，实际 200
        var b = baseline();
        var o = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), outcome("COMPLETED", "NOT_REQUIRED",
                List.of(ok("step:login", "200"), failed("step:create", "200", "Business verification failed: status")),
                Map.of("login", "PASS", "create", "FAIL")), 1);
        assertEquals(Conclusion.CONTRADICTED, o.conclusion());
        assertTrue(o.feedback().stream().anyMatch(f -> f.type() == FeedbackType.CONTRACT_MISMATCH && f.route() == Route.HUMAN && "status".equals(f.assertionId())));
        assertTrue(o.feedback().stream().noneMatch(f -> f.route() == Route.PLANNER));
        var status = CandidateStatusRule.of(b.intent(), b.draft(), o);
        assertEquals(CandidateStatusRule.BLOCKED, status.status());
        assertTrue(status.flags().contains("CONTRADICTED"));
    }

    @Test
    void negativeRejectionWithSideEffectIsContradicted() {
        // AT-G04：拒绝码匹配，但数据侧检查发现写入
        var b = negative("SQL");
        var o = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), outcome("COMPLETED", "NOT_REQUIRED",
                List.of(ok("step:login", "200"), ok("step:create", "422"), failed("verify:create:no_order", "VERIFY_MISMATCH", "rows differ")),
                Map.of("login", "PASS", "create", "FAIL")), 1);
        assertEquals(Conclusion.CONTRADICTED, o.conclusion());
        assertEquals(AssertionClass.MATCHED, o.assertions().stream().filter(a -> a.assertionId().equals("neg_status")).findFirst().orElseThrow().classification());
        assertEquals(AssertionClass.CONTRADICTED, o.assertions().stream().filter(a -> a.assertionId().equals("no_order")).findFirst().orElseThrow().classification());
    }

    @Test
    void preconditionFailureGoesToPlanner() {
        // AT-G05：账号被锁，登录 423
        var b = baseline();
        var o = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), outcome("COMPLETED", "NOT_REQUIRED",
                List.of(failed("step:login", "423", "Business verification failed: status, output:auth.token"), failed("guard:create", "FLOW_BLOCKED", "blocked")),
                Map.of("login", "FAIL", "create", "BLOCKED")), 1);
        assertEquals(Conclusion.PRECONDITION_FAILED, o.conclusion());
        assertTrue(o.feedback().stream().anyMatch(f -> f.type() == FeedbackType.PRECONDITION_FAILED && f.route() == Route.PLANNER && "login".equals(f.callId())));
        assertEquals(AssertionClass.NOT_REACHED, o.assertions().stream().filter(a -> a.callId().equals("create")).findFirst().orElseThrow().classification());
    }

    @Test
    void unobservableDatastoreIsInconclusiveNotContradicted() {
        // AT-G06：SQL 无权限
        var b = negative("SQL");
        var o = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), outcome("COMPLETED", "NOT_REQUIRED",
                List.of(ok("step:login", "200"), ok("step:create", "422"), failed("verify:create:no_order", "VERIFY_UNAVAILABLE", "denied")),
                Map.of("login", "PASS", "create", "ERROR")), 1);
        assertEquals(Conclusion.INCONCLUSIVE, o.conclusion());
        assertTrue(o.feedback().stream().anyMatch(f -> f.type() == FeedbackType.UNOBSERVABLE && f.route() == Route.HUMAN));
    }

    @Test
    void writeWithoutTrustworthyResponseIsSideEffectUnknown() {
        // AT-G07：创建订单超时
        var b = baseline();
        var o = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), outcome("COMPLETED", "NOT_REQUIRED",
                List.of(ok("step:login", "200"), failed("step:create", "Non HTTP response code: java.net.SocketTimeoutException", "timeout")),
                Map.of("login", "PASS", "create", "ERROR")), 1);
        assertEquals(Conclusion.SIDE_EFFECT_UNKNOWN, o.conclusion());
        assertTrue(o.calls().stream().anyMatch(c -> c.callId().equals("create") && c.outcomeUnknown()));
        // 进程被取消时，第一个没有样本的写调用视为可能在途
        var aborted = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), outcome("CANCELLED", "NOT_REQUIRED",
                List.of(ok("step:login", "200")), Map.of("login", "PASS", "create", "NOT_RUN")), 1);
        assertEquals(Conclusion.SIDE_EFFECT_UNKNOWN, aborted.conclusion());
        // 清理失败
        var cleanup = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), outcome("COMPLETED", "FAIL",
                List.of(ok("step:login", "200"), ok("step:create", "201")), Map.of("login", "PASS", "create", "PASS")), 1);
        assertEquals(Conclusion.SIDE_EFFECT_UNKNOWN, cleanup.conclusion());
    }

    @Test
    void notStartedProbeIsNotProbed() {
        var b = baseline();
        var o = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), new ObservationClassifier.ProbeOutcome("run", "REJECTED", false, null, List.of()), 1);
        assertEquals(Conclusion.NOT_PROBED, o.conclusion());
    }

    @Test
    void classificationIsDeterministicAndFeedbackIdsAreStable() {
        var b = baseline();
        var input = outcome("COMPLETED", "NOT_REQUIRED", List.of(failed("step:login", "423", "Business verification failed: status")), Map.of("login", "FAIL", "create", "BLOCKED"));
        var first = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), input, 1);
        var again = ObservationClassifier.observeProbe(b.intent(), b.draft(), b.ir(), input, 2);
        assertEquals(TestingJson.canonical(first.feedback()), TestingJson.canonical(again.feedback()));
        assertEquals(TestingJson.canonical(first.assertions()), TestingJson.canonical(again.assertions()));
    }

    @Test
    void staticInvalidAndMissingExpectationsBlockTheCandidate() {
        var b = negative(null);
        var missing = b.intent().withExpectations(List.of(new Expectation("create", new Assertion("x", "STATUS_EQUALS", null, null), SourceType.MISSING, null, null, "缺少依据")), List.of());
        var draft = new CandidateDraft(missing.intentId(), 1, b.draft().point(), "f", new StaticResult(false, List.of("Literal input type mismatch: integer"), null, true), false, "静态校验未通过", List.of());
        var o = ObservationClassifier.observeStatic(missing, draft, 1);
        assertEquals(Conclusion.STATIC_INVALID, o.conclusion());
        assertTrue(o.feedback().stream().anyMatch(f -> f.type() == FeedbackType.MATERIALIZATION_ERROR && f.route() == Route.PLANNER));
        assertTrue(o.feedback().stream().anyMatch(f -> f.type() == FeedbackType.EXPECTATION_MISSING && f.route() == Route.HUMAN));
        var status = CandidateStatusRule.of(missing, draft, o);
        assertEquals(CandidateStatusRule.BLOCKED, status.status());
        assertTrue(status.flags().containsAll(List.of("STATIC_INVALID", "EXPECTATION_MISSING")));
    }

    @Test
    void leakedRuntimeValuesAreRejectedAndAssertionsComeOnlyFromExpectations() {
        // AT-G11 / OPER-003 / OPER-004
        var b = baseline();
        var leaked = b.intent().withMutable(Map.of("create", Map.of("quantity", new Binding("literal", 2)), "login", Map.of()), Map.of(), null, List.of());
        var digest = IntentMaterializer.LeakCheck.digest(new java.math.BigDecimal("2.0"));
        var m = IntentMaterializer.materialize(leaked, "h", "happy", "t", 2, null, Map.of(), Set.of(digest));
        assertTrue(m.issues().stream().anyMatch(i -> i.startsWith("LEAK:create.quantity")));
        var checked = checks.check(GenerationFixtures.confirmed(flow), m.point(), m.issues(), null);
        assertFalse(checked.result().ok());
        assertTrue(checked.result().materializationError());
        assertEquals(b.draft().point().assertions(), m.point().assertions());
    }
}
