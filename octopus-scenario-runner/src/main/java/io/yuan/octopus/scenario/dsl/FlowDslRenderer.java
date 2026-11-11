package io.yuan.octopus.scenario.dsl;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.preparation.PreparationPlan;
import io.yuan.octopus.scenario.dsl.DslModel.*;
import io.yuan.octopus.scenario.dsl.DslModel.Step;
import io.yuan.octopus.scenario.testing.PollAuthorizationRules;
import java.util.*;
import java.util.function.Function;

/**
 * Ordered Flow → DSL (FR-RT-01/05). Only declared items are written: paths, bindings, outputs (as {@code as}),
 * assertions, waits, polls and cleanup. A flow translated from DSL renders back to DSL that translates to the
 * same flow. Flows the DSL cannot express (legacy DAG, PARALLEL steps, state constraints, datastore checks) are
 * reported as errors instead of being silently dropped.
 */
public final class FlowDslRenderer {
    /** Rendering outcome: the document (when possible) and problems found. */
    public static final class Rendered {
        public final String text;
        public final List<Diagnostic> diagnostics;
        Rendered(String text, List<Diagnostic> diagnostics) { this.text = text; this.diagnostics = diagnostics; }
        public boolean ok() { return text != null; }
    }

    private FlowDslRenderer() {}

    public static Rendered render(Flow flow, Function<String, Operation> operations, Function<String, Optional<PollAuthorizationRules.Grant>> grants) {
        var d = new ArrayList<Diagnostic>();
        if (flow.steps() == null) {
            d.add(DslExpressions.error("RT_LEGACY", "旧版（依赖图）流程不能渲染为 DSL，请先迁移为有序流程", 1, 1, null));
            return new Rendered(null, d);
        }
        for (FlowStep step : flow.steps())
            if (!"SERIAL".equals(step.mode()) || step.calls().size() != 1)
                d.add(DslExpressions.error("RT_PARALLEL", "步骤 " + step.id() + " 包含并行调用；DSL 只表达严格线性的序列，请先改为串行（FR-LIN-01）", 1, 1, null));
        if (!d.isEmpty()) return new Rendered(null, d);
        var doc = new Document();
        doc.feature = flow.name();
        // Aliases: an output key "alias.rest" with path "$.rest" belongs to alias; anything else uses the call id.
        var aliasOf = new HashMap<String, String>();
        var refOf = new HashMap<String, Ref>();
        for (FlowNode node : flow.nodes())
            for (Output o : node.outputs()) {
                String rest = o.path().substring(1);
                String alias;
                if (o.key().contains(".") && o.key().endsWith(rest.replaceAll("\\[(\\d+)]", ".$1")) && !rest.isEmpty()) alias = o.key().substring(0, o.key().indexOf('.'));
                else if (o.key().equals(aliasOrNull(o.key())) && rest.isEmpty()) alias = o.key();
                else {
                    alias = node.id();
                    d.add(new Diagnostic("WARNING", "RT_RENAMED", "上下文名称 " + o.key() + " 将规范化为 ${" + alias + rest + "}", 1, 1, null));
                }
                String previous = aliasOf.putIfAbsent(node.id(), alias);
                if (previous != null && !previous.equals(alias)) {
                    d.add(DslExpressions.error("RT_ALIAS", "调用 " + node.id() + " 的输出无法用一个 as 名称表达", 1, 1, null));
                    return new Rendered(null, d);
                }
                refOf.put(o.key(), new Ref(Ref.Kind.STEP, alias, rest));
            }
        for (TestPoint point : flow.testPoints()) {
            var s = new Scenario();
            s.name = flow.testPoints().size() == 1 ? flow.name() : point.id();
            s.id = flow.flowId().startsWith("dsl.") ? flow.flowId().substring(4) : null;
            if (s.id != null && !s.id.matches("[a-z0-9][a-z0-9-]{0,79}")) s.id = null;
            if (flow.testPoints().size() > 1) s.id = null;
            flow.initialContext().forEach((k, v) -> { if (!v.sensitive()) s.inputs.put(k, v.type()); });
            for (FlowStep fs : flow.steps()) {
                FlowNode node = fs.calls().get(0);
                Operation op = operations.apply(node.operationRef());
                var step = new Step();
                step.method = op.method();
                step.path = op.path();
                if (!node.label().equals(op.method() + " " + op.path())) step.name = node.label();
                step.alias = aliasOf.get(node.id());
                var inputs = new LinkedHashMap<>(node.inputs());
                if (point.inputOverrides() != null) inputs.putAll(point.inputOverrides().getOrDefault(node.id(), Map.of()));
                inputs.forEach((port, binding) -> {
                    Value v = value(binding, refOf, flow, d);
                    if (v == null) return;
                    int dot = port.indexOf('.');
                    String location = dot < 0 ? port : port.substring(0, dot), name = dot < 0 ? "" : port.substring(dot + 1);
                    switch (location) {
                        case "query": step.query.put(name, v); break;
                        case "header": step.headers.put(name, v); break;
                        case "body": if (name.isEmpty()) step.body = v; else step.with.put(name, v); break;
                        default: step.with.put(name, v);
                    }
                });
                if (node.preparation() != null) preparation(node.preparation(), step, refOf, flow, d);
                if (!node.states().isEmpty()) d.add(DslExpressions.error("RT_STATE", "状态迁移约束暂不能用 DSL 表达（" + node.id() + "）", 1, 1, null));
                for (Assertion a : point.assertions().getOrDefault(node.id(), List.of())) expect(a, step, node, d);
                step.waitMillis = node.waitMillis();
                if (node.poll() != null) step.poll = poll(node.poll(), op, grants);
                s.steps.add(step);
                if (fs.id().equals(point.endAtStepRef())) break;
            }
            if (flow.cleanup() != null)
                for (CleanupCall c : flow.cleanup()) {
                    Operation op = operations.apply(c.call().operationRef());
                    var step = new Step();
                    step.method = op.method();
                    step.path = op.path();
                    c.call().inputs().forEach((port, binding) -> {
                        Value v = value(binding, refOf, flow, d);
                        if (v != null) step.with.put(port.substring(port.indexOf('.') + 1), v);
                    });
                    for (Assertion a : c.assertions()) expect(a, step, c.call(), d);
                    s.cleanup.add(step);
                }
            doc.scenarios.add(s);
        }
        if (d.stream().anyMatch(Diagnostic::error)) return new Rendered(null, d);
        return new Rendered(DslRenderer.render(doc), d);
    }

    private static String aliasOrNull(String key) {
        return DslExpressions.ALIAS.matcher(key).matches() ? key : null;
    }

    private static Value value(Binding b, Map<String, Ref> refs, Flow flow, List<Diagnostic> d) {
        switch (b.source()) {
            case "literal": return literal(b.value());
            case "secret": return new Ref(Ref.Kind.SECRET, String.valueOf(b.value()), "");
            case "environment":
                if (b.value() instanceof String) return new Ref(Ref.Kind.ENV, (String) b.value(), "");
                d.add(DslExpressions.error("RT_ENV", "跨模块的环境变量引用暂不能用 DSL 表达", 1, 1, null));
                return null;
            case "context": {
                String key = String.valueOf(b.value());
                if (refs.containsKey(key)) return refs.get(key);
                if (flow.initialContext().containsKey(key)) return new Ref(Ref.Kind.INPUT, key, "");
                d.add(DslExpressions.error("RT_CONTEXT", "未知的上下文引用 " + key, 1, 1, null));
                return null;
            }
            default:
                d.add(DslExpressions.error("RT_BINDING", "不支持的绑定来源 " + b.source(), 1, 1, null));
                return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Value literal(Object v) {
        if (v instanceof Map) {
            var fields = new LinkedHashMap<String, Value>();
            ((Map<String, Object>) v).forEach((k, x) -> fields.put(k, literal(x)));
            return new ObjectValue(fields);
        }
        if (v instanceof List) {
            var items = new ArrayList<Value>();
            ((List<Object>) v).forEach(x -> items.add(literal(x)));
            return new ArrayValue(items);
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return new Literal(((Number) v).longValue());
        if (v instanceof java.math.BigDecimal && ((java.math.BigDecimal) v).stripTrailingZeros().scale() <= 0)
            return new Literal(((java.math.BigDecimal) v).longValueExact());
        if (v instanceof Double || v instanceof Float) return new Literal(new java.math.BigDecimal(v.toString()));
        return new Literal(v);
    }

    /** Reverses the translator's preparation patterns: assembled body, header values, functions and interpolation. */
    private static void preparation(PreparationPlan plan, Step step, Map<String, Ref> refs, Flow flow, List<Diagnostic> d) {
        var calls = new HashMap<String, PreparationPlan.Call>();
        for (var c : plan.tools()) calls.put(c.id(), c);
        if (plan.body() != null) {
            var source = resultCall(plan.body(), calls);
            if (source != null && "json.object".equals(source.tool())) {
                for (var e : source.inputs().entrySet()) {
                    Value v = planValue(e.getValue(), calls, refs, flow, d);
                    if (v != null) step.with.put(e.getKey(), v);
                }
            } else d.add(DslExpressions.error("RT_BODY", "请求体来自非组装动作，暂不能用 DSL 表达", 1, 1, null));
        }
        plan.headers().forEach((name, value) -> {
            Value v = planValue(value, calls, refs, flow, d);
            if (v != null) step.headers.put(name, v);
        });
    }

    private static PreparationPlan.Call resultCall(PreparationPlan.Value v, Map<String, PreparationPlan.Call> calls) {
        if (!"result".equals(v.source())) return null;
        String ref = String.valueOf(v.value());
        return calls.get(ref.substring(0, ref.indexOf('.')));
    }

    private static Value planValue(PreparationPlan.Value v, Map<String, PreparationPlan.Call> calls, Map<String, Ref> refs, Flow flow, List<Diagnostic> d) {
        switch (v.source()) {
            case "literal": return literal(v.value());
            case "secret": return new Ref(Ref.Kind.SECRET, String.valueOf(v.value()), "");
            case "context": return value(new Binding("context", v.value()), refs, flow, d);
            case "result": break;
            default:
                d.add(DslExpressions.error("RT_PREPARATION", "预处理引用 " + v.source() + " 暂不能用 DSL 表达", 1, 1, null));
                return null;
        }
        var call = resultCall(v, calls);
        if (call == null) return null;
        if ("string.value".equals(call.tool()) && call.inputs().size() == 1) return planValue(call.inputs().get("value"), calls, refs, flow, d);
        if ("string.concat".equals(call.tool())) {
            var parts = new ArrayList<Object>();
            for (String key : List.of("prefix", "value")) {
                Value part = planValue(call.inputs().get(key), calls, refs, flow, d);
                if (part == null) return null;
                if (part instanceof Interpolation) parts.addAll(((Interpolation) part).parts);
                else if (part instanceof Literal && ((Literal) part).value instanceof String) parts.add(((Literal) part).value);
                else parts.add(part);
            }
            return new Interpolation(merge(parts));
        }
        var args = new ArrayList<Value>();
        for (var e : call.inputs().values()) {
            Value a = planValue(e, calls, refs, flow, d);
            if (a == null) return null;
            args.add(a);
        }
        return new Call(call.tool(), "1".equals(call.version()) && !call.tool().startsWith("x.") && !call.tool().startsWith("jm.") ? null : call.version(), args);
    }

    private static List<Object> merge(List<Object> parts) {
        var result = new ArrayList<Object>();
        for (Object p : parts) {
            if (p instanceof String && !result.isEmpty() && result.get(result.size() - 1) instanceof String)
                result.set(result.size() - 1, result.get(result.size() - 1) + (String) p);
            else result.add(p);
        }
        return result;
    }

    private static void expect(Assertion a, Step step, FlowNode node, List<Diagnostic> d) {
        var e = new Expect();
        switch (a.kind()) {
            case "STATUS_EQUALS":
                e.kind = Expect.Kind.STATUS;
                e.expected = ((Number) a.expected()).longValue();
                break;
            case "JSON_EQUALS":
                e.kind = Expect.Kind.JSON_EQUALS;
                e.path = a.path();
                Object expected = a.expected();
                if (expected instanceof Integer || expected instanceof java.math.BigDecimal && ((java.math.BigDecimal) expected).stripTrailingZeros().scale() <= 0)
                    expected = new java.math.BigDecimal(expected.toString()).longValueExact();
                if (expected instanceof Map || expected instanceof List) {
                    d.add(DslExpressions.error("RT_ASSERTION", "对象或数组预期值暂不能用 DSL 表达（" + node.id() + "/" + a.id() + "）", 1, 1, null));
                    return;
                }
                e.expected = expected;
                break;
            case "JSON_EXISTS":
                e.kind = Expect.Kind.JSON_EXISTS;
                e.path = a.path();
                break;
            case "SCHEMA_CONFORMS":
                if (a.expected() != null) {
                    d.add(DslExpressions.error("RT_ASSERTION", "自定义 Schema 断言暂不能用 DSL 表达", 1, 1, null));
                    return;
                }
                e.kind = Expect.Kind.SCHEMA;
                break;
            default:
                d.add(DslExpressions.error("RT_ASSERTION", a.kind() + " 断言暂不能用 DSL 表达（" + node.id() + "/" + a.id() + "）", 1, 1,
                        "数据层验证的 DSL 写法将在下一增量提供"));
                return;
        }
        step.expects.add(e);
    }

    private static PollSpec poll(Poll poll, Operation op, Function<String, Optional<PollAuthorizationRules.Grant>> grants) {
        var spec = new PollSpec();
        var grant = poll.authorizationRef() == null ? Optional.<PollAuthorizationRules.Grant>empty() : grants.apply(op.ref());
        if (grant.isPresent() && grant.get().ref().equals(poll.authorizationRef())) {
            long[] defaults = PollAuthorizationRules.defaults(grant.get());
            if (defaults[0] == poll.intervalMillis() && defaults[1] == poll.timeoutMillis()) {
                spec.useAuthorization = true;
                return spec;
            }
        }
        spec.everyMillis = poll.intervalMillis();
        spec.withinMillis = poll.timeoutMillis();
        return spec;
    }
}
