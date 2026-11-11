package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.yuan.octopus.scenario.testing.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * 评审意见（2026-10-27）的回归测试：
 * P1 首轮数据位被丢弃；P1 VALID 数据位只校验类型；P2 嵌套 null 使任务异常终止；P2 多轮模型调用超出调用方时限。
 */
class OperatorDataReviewTest {
    /** 创建订单多一个有约束的普通输入 count（minimum=1），供数据位使用。 */
    static Operation create() {
        var base = GenerationFixtures.create();
        var inputs = new LinkedHashMap<>(base.inputs());
        inputs.put("count", new Port("query", "count", "integer", true, null));
        inputs.put("coupon", new Port("query", "coupon", "string", false, null));
        var params = Map.of("quantity", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                "count", Map.of("type", "integer", "minimum", 1, "maximum", 5),
                "coupon", Map.of("type", "string", "pattern", "^CP-[0-9]{4}$"));
        return new Operation(base.operationId(), base.revision(), base.system(), base.service(), base.method(), base.path(), inputs,
                Map.of("type", "object", "x-octopus-parameters", params), base.responseSchema(), base.implementations(), base.source(), base.requiresCapabilities(), null);
    }

    final Flow flow = flow();
    final Map<String, Operation> ops = operations();
    final FlowModelClient models = mock(FlowModelClient.class);

    static Flow flow() {
        var base = GenerationFixtures.flow(false);
        var steps = base.steps().stream().map(s -> !s.id().equals("s2") ? s : new FlowStep(s.id(), s.label(), s.mode(), s.calls().stream().map(c -> {
            var inputs = new LinkedHashMap<>(c.inputs());
            inputs.put("count", new Binding("literal", 1));
            inputs.put("coupon", new Binding("literal", "CP-0001"));
            return new FlowNode(c.id(), c.label(), c.operationRef(), inputs, c.outputs(), c.states(), c.waitMillis());
        }).toList())).toList();
        return new Flow(base.flowId(), base.revision(), base.name(), base.initialContext(), null, List.of(), List.of(), base.testPoints(), steps, null);
    }

    static Map<String, Operation> operations() {
        var ops = new LinkedHashMap<>(GenerationFixtures.operations());
        ops.put(create().ref(), create());
        return ops;
    }

    BusinessKnowledgeStore store() {
        var store = mock(BusinessKnowledgeStore.class);
        when(store.flow(flow.flowId(), flow.revision())).thenReturn(GenerationFixtures.confirmed(flow));
        ops.forEach((ref, op) -> when(store.operation(ref)).thenReturn(op));
        return store;
    }

    FlowModelClient.VersionedResponse reply(String content) { return new FlowModelClient.VersionedResponse(content, "m", "v"); }

    static String plan(String slots, String data) {
        return "{\"intents\":[{\"intentId\":null,\"parentIntentId\":null,\"title\":\"数量为 0\",\"category\":\"NEGATIVE\",\"callId\":\"create\","
                + "\"overrides\":{\"quantity\":0},\"data\":" + data + ",\"slots\":" + slots + ",\"rejectionStatus\":422,"
                + "\"assertions\":[{\"kind\":\"JSON_EQUALS\",\"path\":\"$.code\",\"expected\":\"QUANTITY_INVALID\"}],"
                + "\"intentionallyViolatedRule\":null,\"rationale\":\"r\",\"evidence\":[],\"preconditionNotes\":null}],\"drops\":[],\"changes\":[],\"questions\":[]}";
    }

    ModelIntentSource.Context context(ModelDeadline deadline) {
        return new ModelIntentSource.Context(flow, "h", GenerationFixtures.happy(), ops, null, 10, null, "m", Set.of(), deadline);
    }

    // ------------------------------------------------------------------ P1：首轮数据位

    @Test
    void firstRoundSlotsReachTheOperatorWithContractConstraints() {
        when(models.casePlan(any(), any(), any())).thenReturn(reply(plan("{\"create\":{\"count\":{\"purpose\":\"VALID\",\"uniqueKey\":false},"
                + "\"coupon\":{\"purpose\":\"VALID\",\"uniqueKey\":false}}}", "null")));
        var intent = new ModelIntentSource(models).plan(context(null)).intents().getFirst();
        assertEquals(Set.of("count", "coupon"), intent.slots().get("create").keySet(), "slots declared in round 1 are kept");
        assertEquals(1, intent.slots().get("create").get("count").constraints().get("minimum"), "constraints come from the contract");

        // 经引擎物化：count 由确定性规则填充，coupon（有 pattern）交给 Operator 模型填充
        when(models.caseMaterialize(any(), any(), any())).thenReturn(reply("{\"bindings\":{\"create\":{\"coupon\":{\"source\":\"literal\",\"value\":\"CP-1234\"}}}}"));
        var store = store();
        var engine = new CaseGenerationEngine(store, new ModelIntentSource(models), new DataSlotFiller(models, null),
                new StaticChecks(GenerationFixtures.planner(store), store), null, CaseGenerationEngine.Budget.DEFAULT, Clock.systemUTC());
        var t = new CaseGenerationEngine.Task();
        t.id = "slots";
        t.request = GenerationFixtures.request(List.of(Strategy.MODEL), ProbeMode.OFF, null);
        t.createdAt = Instant.now().toString();
        while (engine.advance(t, () -> false)) { }
        var draft = t.current().candidates.values().iterator().next();
        var create = draft.point().inputOverrides().get("create");
        assertEquals(new Binding("literal", new java.math.BigDecimal(1)), create.get("count"));
        assertEquals(new Binding("literal", "CP-1234"), create.get("coupon"));
        verify(models, times(1)).caseMaterialize(any(), any(), any());
        assertTrue(draft.staticResult().ok(), String.valueOf(draft.staticResult().errors()));
    }

    // ------------------------------------------------------------------ P1：VALID 约束

    @Test
    void modelValuesMustSatisfyValidSlotConstraints() {
        var slots = Map.of("create", Map.of(
                "count", new Slot("integer", "VALID", SlotConstraints.of(create(), "count"), false),
                "coupon", new Slot("string", "VALID", SlotConstraints.of(create(), "coupon"), false)));
        List<String> issues = new ArrayList<>();
        var accepted = DataSlotFiller.validate(new DataSlotFiller.MaterializeOutput(Map.of("create", Map.of(
                "count", new Binding("literal", 0), "coupon", new Binding("literal", "COUPON")))), slots, flow, ops, issues);
        assertTrue(accepted.isEmpty(), "0 violates minimum=1 and COUPON violates the pattern");
        assertTrue(issues.stream().anyMatch(i -> i.startsWith("数据位") && i.contains("create.count")), String.valueOf(issues));
        assertTrue(issues.stream().anyMatch(i -> i.startsWith("数据位") && i.contains("create.coupon")));

        issues.clear();
        var ok = DataSlotFiller.validate(new DataSlotFiller.MaterializeOutput(Map.of("create", Map.of(
                "count", new Binding("literal", 3), "coupon", new Binding("literal", "CP-0042")))), slots, flow, ops, issues);
        assertEquals(2, ok.get("create").size(), String.valueOf(issues));

        // VIOLATING 数据位必须确实违反约束
        issues.clear();
        var violating = Map.of("create", Map.of("count", new Slot("integer", "VIOLATING", SlotConstraints.of(create(), "count"), false)));
        assertTrue(DataSlotFiller.validate(new DataSlotFiller.MaterializeOutput(Map.of("create", Map.of("count", new Binding("literal", 2)))), violating, flow, ops, issues).isEmpty());
        assertFalse(DataSlotFiller.validate(new DataSlotFiller.MaterializeOutput(Map.of("create", Map.of("count", new Binding("literal", 9)))), violating, flow, ops, new ArrayList<>()).isEmpty());

        // 被拒绝的取值使候选静态无效，并作为可由 Planner 处理的物化错误
        var intent = RuleIntentSource.plan(flow, GenerationFixtures.happy(), ops, null, Set.of()).intents().getFirst()
                .withMutable(Map.of(), slots, null, List.of());
        var m = IntentMaterializer.materialize(intent, "h", "happy", "t", 1, null, Map.of(), Set.of());
        var all = new ArrayList<>(List.of("数据位：模型取值不满足约束：create.count"));
        all.addAll(m.issues());
        var store = store();
        var checked = new StaticChecks(GenerationFixtures.planner(store), store).check(GenerationFixtures.confirmed(flow), m.point(), all, null);
        assertFalse(checked.result().ok());
        assertTrue(checked.result().materializationError());
    }

    @Test
    void plannerDataLiteralsAreCheckedAgainstTheContractToo() {
        when(models.casePlan(any(), any(), any())).thenReturn(reply(plan("null", "{\"create\":{\"count\":0,\"coupon\":\"CP-0007\"}}")));
        var intent = new ModelIntentSource(models).plan(context(null)).intents().getFirst();
        assertFalse(intent.dataPlan().getOrDefault("create", Map.of()).containsKey("count"));
        assertEquals(new Binding("literal", "CP-0007"), intent.dataPlan().get("create").get("coupon"));
        assertTrue(intent.issues().stream().anyMatch(i -> i.contains("create.count")));
    }

    // ------------------------------------------------------------------ P2：嵌套 null

    @Test
    void nestedNullsAreFormatProblemsNotCrashes() {
        var slots = Map.of("create", Map.of("count", new Slot("integer", "VALID", Map.of("format", "custom"), false)));
        List<String> issues = new ArrayList<>();
        var nullRow = new HashMap<String, Map<String, Binding>>();
        nullRow.put("create", null);
        nullRow.put("login", null);
        assertDoesNotThrow(() -> DataSlotFiller.validate(new DataSlotFiller.MaterializeOutput(nullRow), slots, flow, ops, issues));
        assertTrue(issues.stream().anyMatch(i -> i.contains("模型未填充数据位")));

        when(models.caseMaterialize(any(), any(), any())).thenReturn(reply("{\"bindings\":{\"create\":null}}"), reply("{\"bindings\":{\"create\":{\"count\":null}}}"),
                reply("{\"bindings\":{\"create\":{\"count\":{\"source\":null,\"value\":null}}}}"));
        var intent = RuleIntentSource.plan(flow, GenerationFixtures.happy(), ops, null, Set.of()).intents().getFirst().withMutable(Map.of(), slots, null, List.of());
        var fill = assertDoesNotThrow(() -> new DataSlotFiller(models, null).fill(intent, flow, ops, "m"));
        assertFalse(fill.bindings().containsKey("create"));
        assertFalse(fill.issues().isEmpty());

        // Planner 输出中的嵌套 null：意图照常构造，问题记入 issues
        when(models.casePlan(any(), any(), any())).thenReturn(reply(plan("{\"create\":null,\"login\":{\"body\":null}}", "{\"create\":null}")));
        var planned = assertDoesNotThrow(() -> new ModelIntentSource(models).plan(context(null)));
        assertEquals(1, planned.intents().size());
        assertTrue(planned.intents().getFirst().issues().stream().anyMatch(i -> i.contains("格式无效") || i.contains("数据位无效")));
    }

    @Test
    void structurallyBrokenPlannerOutputGoesThroughBoundedFormatCorrection() {
        when(models.casePlan(any(), any(), any())).thenReturn(reply("{\"intents\":[{\"callId\":\"create\",\"overrides\":null,\"assertions\":[null]}],\"drops\":null,\"changes\":null,\"questions\":[null]}"));
        var planned = new ModelIntentSource(models).plan(context(null));
        assertEquals(1, planned.intents().size());
        // 每次都是无法解析的结构：3 次后以 MODEL_RESPONSE_INVALID 结束，而不是 NullPointerException
        reset(models);
        when(models.casePlan(any(), any(), any())).thenReturn(reply("{\"intents\":{\"oops\":1}}"));
        var error = assertThrows(FlowFailure.class, () -> new ModelIntentSource(models).plan(context(null)));
        assertEquals(FlowFailure.Code.MODEL_RESPONSE_INVALID, error.code);
        verify(models, times(3)).casePlan(any(), any(), any());
    }

    // ------------------------------------------------------------------ P2：多轮调用的总时限

    static final class StepClock extends Clock {
        long millis = 1_000_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public long millis() { return millis; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    @Test
    void sequentialModelRoundsStopWhenTheCallersDeadlineIsSpent() {
        var clock = new StepClock();
        var deadline = ModelDeadline.after(Duration.ofSeconds(240), clock);
        List<Duration> timeouts = new ArrayList<>();
        when(models.casePlan(any(), any(), any())).thenAnswer(inv -> {
            timeouts.add(inv.getArgument(2));
            clock.millis += 110_000;   // 每轮模型调用耗时 110 秒，返回无效结构
            return reply("not json");
        });
        var error = assertThrows(FlowFailure.class, () -> new ModelIntentSource(models).plan(context(deadline)));
        assertEquals(FlowFailure.Code.MODEL_TIMEOUT, error.code, "the third round would end after the caller stopped waiting");
        assertEquals(2, timeouts.size());
        assertTrue(timeouts.get(0).toSeconds() <= 200 && timeouts.get(1).toSeconds() <= 120, String.valueOf(timeouts));
        assertTrue(clock.millis - 1_000_000 < 270_000, "finished before the 270 s browser timeout");
    }

    @Test
    void reviewAgentModelCallEndsBeforeTheClientTimeout() {
        var body = FlowModelClient.bounded("m", Map.of(), Duration.ofSeconds(120));
        assertEquals(115, body.get("timeoutSeconds"));
        assertEquals(5, FlowModelClient.bounded("m", Map.of(), Duration.ofSeconds(3)).get("timeoutSeconds"));
    }
}
