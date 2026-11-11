package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;

import io.yuan.octopus.scenario.testing.OrderedFlows;
import java.util.*;
import org.junit.jupiter.api.Test;

/** PLAN-002 预期来源校验与 PLAN-005～007 修订守卫（AT-G03、AT-G09）。 */
class PlanGuardTest {
    final Flow flow = GenerationFixtures.flow(false);
    final Map<String, Operation> ops = GenerationFixtures.operations();

    TestIntent intent(Expectation... expectations) {
        var i = new TestIntent(null, null, Strategy.MODEL, "llm/create", "t", "NEGATIVE", List.of("create"),
                new Variation("create", Map.of("quantity", new Binding("literal", 0)), "llm", true), "s2", List.of("create"), null,
                List.of(expectations), Map.of(), Map.of(), null, "r", List.of());
        return i.withId(PlanGuard.intentId(i));
    }

    Expectation contract(String status, String kind, String path, Object expected) {
        return new Expectation("create", new Assertion("a" + kind + path, kind, path, expected), SourceType.CONTRACT,
                GenerationFixtures.create().ref() + "#response/" + status + (path == null ? "" : "/" + path), null, null);
    }

    @Test
    void contractSourcesMustBeDeclaredStatusesPathsAndEnumValues() {
        var checked = PlanGuard.checkSources(intent(
                contract("422", "STATUS_EQUALS", null, 422),
                contract("409", "STATUS_EQUALS", null, 409),
                contract("422", "JSON_EQUALS", "$.code", "QUANTITY_INVALID"),
                contract("422", "JSON_EQUALS", "$.message", "数量非法"),
                contract("422", "JSON_EXISTS", "$.message", true),
                contract("422", "JSON_EXISTS", "$.errorCode", true),
                contract("422", "STATUS_EQUALS", null, 400)), flow, ops, null);
        assertEquals(List.of(SourceType.CONTRACT, SourceType.MISSING, SourceType.CONTRACT, SourceType.MISSING, SourceType.CONTRACT, SourceType.MISSING, SourceType.MISSING),
                checked.stream().map(Expectation::sourceType).toList());
        assertTrue(checked.get(1).note().contains("409"));
        assertEquals("数量非法", checked.get(3).proposed(), "the unbacked value is kept only as a suggestion");
        assertNull(checked.get(3).assertion().expected());
    }

    @Test
    void humanSourcesMustMatchTheConfirmedPointOrTheRequestRejection() {
        var exact = new Expectation("create", new Assertion("status", "STATUS_EQUALS", null, 201), SourceType.HUMAN, "point/happy/status", null, null);
        var changed = new Expectation("create", new Assertion("status", "STATUS_EQUALS", null, 200), SourceType.HUMAN, "point/happy/status", null, null);
        var rejection = new Expectation("create", new Assertion("rejection", "STATUS_EQUALS", null, 422), SourceType.HUMAN, PlanGuard.REJECTION_REF, null, null);
        var checked = PlanGuard.checkSources(intent(exact, changed, rejection), flow, ops, new Rejection(422, "规范 §2"));
        assertEquals(List.of(SourceType.HUMAN, SourceType.MISSING, SourceType.HUMAN), checked.stream().map(Expectation::sourceType).toList());
        var withoutEvidence = PlanGuard.checkSources(intent(rejection), flow, ops, new Rejection(422, " "));
        assertEquals(SourceType.MISSING, withoutEvidence.getFirst().sourceType());
    }

    @Test
    void freeTextBusinessRulesCannotBackAnExpectation() {
        var e = new Expectation("create", new Assertion("s", "JSON_EQUALS", "$.status", "CREATED"), SourceType.RULE, "businessRule/r1", null, null);
        assertEquals(SourceType.MISSING, PlanGuard.checkSources(intent(e), flow, ops, null).getFirst().sourceType());
    }

    GenerationPlan plan(TestIntent... intents) {
        return new GenerationPlan(1, List.of(intents), List.of(), List.of(), List.of(), List.of(), intents.length, false, null);
    }

    @Test
    void revisionMayOnlyChangeDataWithRoutedFeedback() {
        var base = intent(contract("422", "STATUS_EQUALS", null, 422));
        var data = base.withMutable(Map.of("login", Map.of()), Map.of(), "换一个未锁定的账号", List.of());
        var noFeedback = new GenerationPlan(2, List.of(data), List.of(), List.of(), List.of(), List.of(), 1, false, null);
        assertFalse(PlanGuard.checkRevision(plan(base), noFeedback, Set.of("fb_1"), Set.of()).ok());
        var withFeedback = new GenerationPlan(2, List.of(data), List.of(), List.of(new Change(base.intentId(), List.of("fb_1"), "换账号")), List.of(), List.of(), 1, false, null);
        assertTrue(PlanGuard.checkRevision(plan(base), withFeedback, Set.of("fb_1"), Set.of()).ok());
        var foreignFeedback = new GenerationPlan(2, List.of(data), List.of(), List.of(new Change(base.intentId(), List.of("fb_human"), "x")), List.of(), List.of(), 1, false, null);
        assertFalse(PlanGuard.checkRevision(plan(base), foreignFeedback, Set.of("fb_1"), Set.of()).ok());
    }

    @Test
    void revisionCannotTouchExpectationsTargetsOrFrozenIntents() {
        var base = intent(contract("422", "STATUS_EQUALS", null, 422));
        // AT-G03：把预期改成实际观测到的 200，即使保留 intentId 也被拒绝
        var rewritten = new TestIntent(base.intentId(), null, base.source(), base.sourceRule(), base.title(), base.category(), base.targetCallIds(), base.variation(),
                base.endAtStepRef(), base.suppressedOutputCalls(), null, List.of(contract("200", "STATUS_EQUALS", null, 200)), Map.of(), Map.of(), null, "r", List.of());
        var change = List.of(new Change(base.intentId(), List.of("fb_1"), "x"));
        var check = PlanGuard.checkRevision(plan(base), new GenerationPlan(2, List.of(rewritten), List.of(), change, List.of(), List.of(), 1, false, null), Set.of("fb_1"), Set.of());
        assertFalse(check.ok());
        assertTrue(check.violations().stream().anyMatch(v -> v.contains("不可修改")));
        // 矛盾的意图不能丢弃、不能修订
        var drop = new GenerationPlan(2, List.of(), List.of(new Drop(base.intentId(), "无法修复")), List.of(), List.of(), List.of(), 0, false, null);
        assertFalse(PlanGuard.checkRevision(plan(base), drop, Set.of(), Set.of(base.intentId())).ok());
        assertTrue(PlanGuard.checkRevision(plan(base), drop, Set.of(), Set.of()).ok());
        var vanish = new GenerationPlan(2, List.of(), List.of(), List.of(), List.of(), List.of(), 0, false, null);
        assertFalse(PlanGuard.checkRevision(plan(base), vanish, Set.of(), Set.of()).ok());
    }

    @Test
    void splitsMustKeepParentExpectationsAndCiteFeedback() {
        var base = intent(contract("422", "STATUS_EQUALS", null, 422));
        var split = new TestIntent("it_split", base.intentId(), base.source(), base.sourceRule(), "拆分", base.category(), base.targetCallIds(),
                base.variation(), base.endAtStepRef(), base.suppressedOutputCalls(), null, base.expectations(), Map.of("login", Map.of()), Map.of(), null, "r", List.of());
        var ok = new GenerationPlan(2, List.of(base, split), List.of(), List.of(new Change(base.intentId(), List.of("fb_1"), "拆分")), List.of(), List.of(), 2, false, null);
        assertTrue(PlanGuard.checkRevision(plan(base), ok, Set.of("fb_1"), Set.of()).ok());
        var orphan = new TestIntent("it_orphan", null, base.source(), base.sourceRule(), "新", base.category(), base.targetCallIds(),
                base.variation(), base.endAtStepRef(), base.suppressedOutputCalls(), null, base.expectations(), Map.of(), Map.of(), null, "r", List.of());
        assertFalse(PlanGuard.checkRevision(plan(base), new GenerationPlan(2, List.of(base, orphan), List.of(), List.of(), List.of(), List.of(), 2, false, null), Set.of("fb_1"), Set.of()).ok());
    }

    @Test
    void ruleSourceProducesStableIdsAndHumanExpectations() {
        var first = RuleIntentSource.plan(flow, GenerationFixtures.happy(), ops, null, Set.of());
        var second = RuleIntentSource.plan(flow, GenerationFixtures.happy(), ops, null, Set.of());
        assertEquals(first.intents().stream().map(TestIntent::intentId).toList(), second.intents().stream().map(TestIntent::intentId).toList());
        assertTrue(first.intents().getFirst().baseline());
        assertTrue(first.intents().getFirst().expectations().stream().allMatch(e -> e.sourceType() == SourceType.HUMAN));
        var negative = first.intents().stream().filter(i -> "NEGATIVE".equals(i.category())).findFirst().orElseThrow();
        assertEquals("s2", negative.endAtStepRef());
        assertTrue(negative.expectations().stream().anyMatch(e -> e.sourceType() == SourceType.MISSING), "no rejection evidence → missing oracle");
        assertNotNull(OrderedFlows.prefix(flow, GenerationFixtures.happy()));
    }
}
