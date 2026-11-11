package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Planner 输出的守卫：预期来源校验（PLAN-002）与跨轮修订差异校验（PLAN-005～007）。
 * 纯函数，不访问存储，便于逐条单测。
 */
public final class PlanGuard {
    private PlanGuard() {}

    private static final Pattern CONTRACT_REF = Pattern.compile("(.+)#response/([1-5][0-9]{2})(/.*)?");
    private static final Pattern RULE_REF = Pattern.compile("stateModel/([^/]+)/([^/]+)");
    private static final Pattern HUMAN_POINT_REF = Pattern.compile("point/([^/]+)/([^/]+)");
    public static final String REJECTION_REF = "request/rejection";

    /** 不可变字段：类别、目标调用、焦点输入、范围、压制输出、违反规则、预期。 */
    public static String immutableHash(TestIntent intent) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("category", intent.category());
        identity.put("targetCallIds", intent.targetCallIds());
        identity.put("variation", intent.variation());
        identity.put("endAtStepRef", intent.endAtStepRef());
        identity.put("suppressedOutputCalls", intent.suppressedOutputCalls());
        identity.put("rule", intent.intentionallyViolatedRule());
        identity.put("expectations", intent.expectations());
        return hash(identity);
    }

    public static String mutableHash(TestIntent intent) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("dataPlan", intent.dataPlan());
        identity.put("slots", intent.slots());
        identity.put("preconditionNotes", intent.preconditionNotes());
        return hash(identity);
    }

    /** 任务内稳定的意图 ID：由不可变字段决定，同一意图跨轮 ID 相同。 */
    public static String intentId(TestIntent intent) {
        return "it_" + immutableHash(intent).substring(0, 20);
    }

    public static String planMutableHash(GenerationPlan plan) {
        var parts = new TreeMap<String, String>();
        for (var intent : plan.intents()) parts.put(intent.intentId(), mutableHash(intent));
        return hash(Map.of("intents", parts, "drops", plan.drops().stream().map(Drop::intentId).sorted().toList()));
    }

    // ------------------------------------------------------------------ PLAN-002

    /**
     * 逐条校验预期来源。引用不存在或不能支撑期望值时改记为 MISSING（不丢弃意图），并在 note 中说明原因。
     */
    public static List<Expectation> checkSources(TestIntent intent, Flow flow, Map<String, Operation> operations, Rejection rejection) {
        Map<String, FlowNode> calls = new HashMap<>();
        flow.nodes().forEach(n -> calls.put(n.id(), n));
        List<Expectation> result = new ArrayList<>();
        for (var e : intent.expectations()) {
            if (e.sourceType() == SourceType.MISSING) { result.add(e); continue; }
            String problem = problem(e, calls.get(e.callId()), operations, flow, rejection);
            result.add(problem == null ? e : e.withMissing(problem));
        }
        return result;
    }

    static String problem(Expectation e, FlowNode call, Map<String, Operation> operations, Flow flow, Rejection rejection) {
        if (call == null) return "预期引用了流程中不存在的调用";
        var a = e.assertion();
        if (a == null || a.id() == null || a.kind() == null) return "预期结构不完整";
        if (e.sourceRef() == null || e.sourceRef().isBlank()) return "缺少来源引用";
        switch (e.sourceType()) {
            case CONTRACT -> {
                var m = CONTRACT_REF.matcher(e.sourceRef());
                if (!m.matches()) return "契约引用格式无效";
                if (!m.group(1).equals(call.operationRef())) return "契约引用不是该调用使用的接口版本";
                var operation = operations.get(call.operationRef());
                Object schema = operation == null || operation.responseSchema() == null ? null : operation.responseSchema().get(m.group(2));
                if (schema == null) return "契约未声明状态码 " + m.group(2);
                switch (a.kind()) {
                    case "STATUS_EQUALS" -> {
                        if (!(a.expected() instanceof Number n) || n.intValue() != Integer.parseInt(m.group(2))) return "状态码预期与契约引用不一致";
                        return null;
                    }
                    case "JSON_EXISTS", "SCHEMA_CONFORMS" -> {
                        return a.path() != null && declared(schema, a.path()) ? null : "断言路径未在 " + m.group(2) + " 回应契约中定义";
                    }
                    case "JSON_EQUALS" -> {
                        if (a.path() == null || !declared(schema, a.path())) return "断言路径未在 " + m.group(2) + " 回应契约中定义";
                        Object field = at(schema, a.path());
                        if (field instanceof Map<?, ?> f) {
                            if (f.containsKey("const") && canonical(f.get("const")).equals(canonical(a.expected()))) return null;
                            if (f.get("enum") instanceof List<?> values && values.stream().anyMatch(v -> canonical(v).equals(canonical(a.expected())))) return null;
                        }
                        return "契约只声明了字段，没有声明该取值（需要人工依据）";
                    }
                    default -> { return "契约来源不支持断言类型 " + a.kind(); }
                }
            }
            case RULE -> {
                var m = RULE_REF.matcher(e.sourceRef());
                if (!m.matches()) return "业务规则引用格式无效（仅支持已确认状态模型 stateModel/<modelId>/<ruleId>）";
                var model = flow.stateModels().stream().filter(s -> s.modelId().equals(m.group(1))).findFirst().orElse(null);
                if (model == null || model.transitions().stream().noneMatch(t -> t.ruleId().equals(m.group(2)))) return "状态规则不存在于已确认流程";
                if (!"JSON_EQUALS".equals(a.kind()) || !model.states().contains(String.valueOf(a.expected()))) return "状态规则只能支撑取值为该模型状态的 JSON_EQUALS 断言";
                return null;
            }
            case HUMAN -> {
                if (REJECTION_REF.equals(e.sourceRef())) {
                    if (rejection == null || rejection.status() == null || rejection.evidence() == null || rejection.evidence().isBlank()) return "请求未提供拒绝状态及依据";
                    if (!"STATUS_EQUALS".equals(a.kind()) || !(a.expected() instanceof Number n) || n.intValue() != rejection.status()) return "与请求提供的拒绝状态不一致";
                    return null;
                }
                var m = HUMAN_POINT_REF.matcher(e.sourceRef());
                if (!m.matches()) return "人工来源引用格式无效";
                var point = flow.testPoints().stream().filter(p -> p.id().equals(m.group(1))).findFirst().orElse(null);
                if (point == null) return "引用的已确认测试点不存在";
                var match = point.assertions().getOrDefault(e.callId(), List.of()).stream().filter(x -> x.id().equals(m.group(2))).findFirst().orElse(null);
                if (match == null || !canonical(match).equals(canonical(new Assertion(match.id(), a.kind(), a.path(), a.expected())))) return "与已确认测试点中的断言不一致";
                return null;
            }
            default -> { return "未知来源类型"; }
        }
    }

    /** 回应字段路径是否在 Schema 中声明（逐级 properties / items）；路径 $ 表示整个回应体。 */
    public static boolean declared(Object schema, String path) {
        return at(schema, path) != null;
    }

    static Object at(Object schema, String path) {
        if (path == null || !path.startsWith("$")) return null;
        if (path.equals("$.body")) path = "$";
        else if (path.startsWith("$.body.") || path.startsWith("$.body[")) path = "$" + path.substring(6);
        Object current = schema;
        var tokens = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\[([0-9]+)]").matcher(path.substring(1));
        int consumed = 0;
        while (tokens.find()) {
            if (tokens.start() != consumed) return null;
            consumed = tokens.end();
            if (!(current instanceof Map<?, ?> map)) return null;
            if (tokens.group(1) != null && map.get("properties") instanceof Map<?, ?> props) current = props.get(tokens.group(1));
            else if (tokens.group(2) != null && map.get("items") != null) current = map.get("items");
            else return null;
        }
        if (consumed != path.length() - 1) return null;
        return current;
    }

    // ------------------------------------------------------------------ PLAN-005～007

    public record RevisionCheck(boolean ok, List<String> violations) {}

    /**
     * 校验第 2 轮起的计划修订。
     *
     * @param plannerFeedback 上一轮路由给 PLANNER 的反馈 ID
     * @param frozen          被判 CONTRADICTED（或副作用未知）的意图：不得丢弃、拆分或修改
     */
    public static RevisionCheck checkRevision(GenerationPlan previous, GenerationPlan next, Set<String> plannerFeedback, Set<String> frozen) {
        List<String> violations = new ArrayList<>();
        Map<String, TestIntent> before = new LinkedHashMap<>();
        previous.intents().forEach(i -> before.put(i.intentId(), i));
        Map<String, TestIntent> after = new LinkedHashMap<>();
        for (var intent : next.intents()) {
            if (after.put(intent.intentId(), intent) != null) violations.add("意图重复：" + intent.intentId());
        }
        Map<String, Set<String>> changeRefs = new HashMap<>();
        for (var change : next.changes()) {
            if (change.intentId() == null) { violations.add("变更缺少意图 ID"); continue; }
            var refs = change.feedbackIds() == null ? List.<String>of() : change.feedbackIds();
            if (refs.isEmpty()) violations.add("变更没有对应的反馈：" + change.intentId());
            for (String ref : refs) if (!plannerFeedback.contains(ref)) violations.add("变更引用了不可由 Planner 处理的反馈：" + ref);
            changeRefs.computeIfAbsent(change.intentId(), k -> new HashSet<>()).addAll(refs);
        }
        Set<String> dropped = new HashSet<>();
        for (var drop : next.drops()) {
            if (drop.intentId() == null || !before.containsKey(drop.intentId())) { violations.add("丢弃了不存在的意图"); continue; }
            if (drop.reason() == null || drop.reason().isBlank()) violations.add("丢弃意图需要原因：" + drop.intentId());
            if (frozen.contains(drop.intentId())) violations.add("矛盾或副作用未知的意图只能交给人工，不能丢弃：" + drop.intentId());
            dropped.add(drop.intentId());
        }
        for (var entry : before.entrySet()) {
            String id = entry.getKey();
            var old = entry.getValue();
            var now = after.get(id);
            if (now == null) {
                if (!dropped.contains(id)) violations.add("意图消失但未声明丢弃：" + id);
                continue;
            }
            if (dropped.contains(id)) violations.add("同一意图既保留又丢弃：" + id);
            if (!immutableHash(old).equals(immutableHash(now)) || !Objects.equals(old.source(), now.source()))
                violations.add("不可修改意图的预期、目标、焦点输入或范围：" + id);
            boolean mutated = !mutableHash(old).equals(mutableHash(now));
            if (mutated && frozen.contains(id)) violations.add("矛盾或副作用未知的意图不能修订：" + id);
            if (mutated && !changeRefs.containsKey(id)) violations.add("修改了数据计划但未列出对应反馈：" + id);
        }
        for (var entry : after.entrySet()) {
            if (before.containsKey(entry.getKey())) continue;
            var intent = entry.getValue();
            var parent = intent.parentIntentId() == null ? null : before.get(intent.parentIntentId());
            if (parent == null) { violations.add("新增意图只能是对已有意图的拆分：" + entry.getKey()); continue; }
            if (frozen.contains(parent.intentId())) violations.add("矛盾或副作用未知的意图不能拆分：" + parent.intentId());
            if (!Objects.equals(parent.category(), intent.category()) || !Objects.equals(parent.targetCallIds(), intent.targetCallIds())
                    || !Objects.equals(parent.endAtStepRef(), intent.endAtStepRef())
                    || !canonical(parent.expectations()).equals(canonical(intent.expectations()))
                    || !Objects.equals(parent.intentionallyViolatedRule(), intent.intentionallyViolatedRule()))
                violations.add("拆分意图必须与父意图的预期、目标和范围相同：" + entry.getKey());
            if (!changeRefs.containsKey(entry.getKey()) && !changeRefs.containsKey(parent.intentId()))
                violations.add("拆分未列出对应反馈：" + entry.getKey());
        }
        for (String id : changeRefs.keySet())
            if (!after.containsKey(id) && !dropped.contains(id)) violations.add("变更引用了不存在的意图：" + id);
        return new RevisionCheck(violations.isEmpty(), List.copyOf(new LinkedHashSet<>(violations)));
    }
}
