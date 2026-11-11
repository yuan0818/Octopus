package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;

import io.yuan.octopus.scenario.testing.OrderedFlows;
import java.math.BigDecimal;
import java.util.*;

/**
 * Planner 的确定性规则来源（ADR-CG-02）：由原 CaseCandidateService 的变体规则改写，产出统一的 {@link TestIntent}。
 * 预期只引用已确认测试点的断言（HUMAN）或请求中提供的拒绝依据；缺失的预期记为 MISSING。
 */
public final class RuleIntentSource {
    private RuleIntentSource() {}

    public static final String BASELINE_NOTE = "检查数据隔离与业务预期是否适用于本次目标";

    public record Result(List<TestIntent> intents, List<String> warnings) {}

    public static Result plan(Flow flow, TestPoint base, Map<String, Operation> operations, Rejection rejection, Set<String> coverage) {
        List<TestIntent> intents = new ArrayList<>();
        var warnings = new LinkedHashSet<String>();
        warnings.add("首期按单个标量参数生成边界；对象字段、格式、组合约束和参数组合需人工补充，不代表穷尽覆盖");
        var groups = OrderedFlows.prefix(flow, base);
        intents.add(intent(base, "CONFIRMED_BASELINE", base.title() + " · 正向", "BASELINE", base.covers(), null,
                base.endAtStepRef(), base.suppressedOutputCalls(), humanExpectations(base, null), base.inputOverrides(), List.of(), BASELINE_NOTE));
        for (var group : groups) for (var call : group.calls()) {
            var operation = operations.get(call.operationRef());
            for (var entry : operation.inputs().entrySet()) {
                String input = entry.getKey();
                var port = entry.getValue();
                Object schema = "body".equals(port.location()) && "$".equals(port.name()) ? operation.requestSchema()
                        : operation.requestSchema() != null && operation.requestSchema().get("x-octopus-parameters") instanceof Map<?, ?> map ? map.get(input) : null;
                if (!(schema instanceof Map<?, ?> rule)) continue;
                if (rule.get("enum") instanceof List<?> values && values.size() > 100) warnings.add(operation.ref() + "/" + input + "：枚举超过 100 项，仅取前 100 项");
                for (var variation : variations(rule, port.type())) {
                    String category = variation.negative() ? "NEGATIVE" : "enum".equals(variation.rule()) ? "ENUM" : "BOUNDARY";
                    if (!coverage.isEmpty() && !coverage.contains(category)) continue;
                    List<String> issues = new ArrayList<>();
                    String end = base.endAtStepRef();
                    List<String> covers = base.covers();
                    List<String> suppressed = base.suppressedOutputCalls();
                    Map<String, Map<String, Binding>> data = new LinkedHashMap<>(base.inputOverrides());
                    List<Expectation> expectations;
                    if (variation.negative()) {
                        end = group.id();
                        var included = included(flow, end);
                        data.keySet().retainAll(included);
                        covers = List.of(call.id());
                        if ("SERIAL".equals(group.mode()) && !group.calls().getLast().id().equals(call.id())) issues.add("目标调用不是串行步骤末尾，需要先拆分步骤");
                        expectations = new ArrayList<>(humanExpectations(base, included).stream().filter(e -> !e.callId().equals(call.id())).toList());
                        if (rejection == null || rejection.status() == null) {
                            issues.add("缺少明确的拒绝响应及无副作用预期");
                            expectations.add(new Expectation(call.id(), new Assertion("rejection", "STATUS_EQUALS", null, null), SourceType.MISSING, null, null, "缺少明确的拒绝响应"));
                        } else {
                            expectations.add(new Expectation(call.id(), new Assertion("rejection", "STATUS_EQUALS", null, rejection.status()), SourceType.HUMAN, PlanGuard.REJECTION_REF, null, null));
                            issues.add("请补充并审阅无副作用业务断言；依据：" + rejection.evidence());
                        }
                        expectations.add(new Expectation(call.id(), new Assertion("no_side_effect", "JSON_EQUALS", null, null), SourceType.MISSING, null, null, "需要无副作用业务断言"));
                        suppressed = List.of(call.id());
                    } else {
                        expectations = humanExpectations(base, null);
                    }
                    if (!matches(variation.value(), port.type())) issues.add("当前参数类型不支持此违规值，需要明确负向输入能力");
                    if (!variation.negative() && !validScalar(variation.value(), rule)) issues.add("候选还需满足同一参数的其他契约约束，请人工提供合法值");
                    if (rule.keySet().stream().anyMatch(key -> Set.of("pattern", "format", "allOf", "anyOf", "oneOf", "not", "$ref").contains(key)))
                        issues.add("当前生成器未验证格式或组合约束，请人工审阅");
                    var focus = new Variation(call.id(), Map.of(input, new Binding("literal", variation.value())), variation.rule(), variation.negative());
                    intents.add(intent(base, operation.ref() + "/" + input + "/" + variation.rule(), call.label() + " · " + input + " " + variation.rule(),
                            category, covers, focus, end, suppressed, expectations, data, issues, null));
                }
            }
        }
        return new Result(intents, List.copyOf(warnings));
    }

    static Set<String> included(Flow flow, String endStep) {
        var included = new LinkedHashSet<String>();
        for (var s : flow.steps()) { s.calls().forEach(c -> included.add(c.id())); if (s.id().equals(endStep)) break; }
        return included;
    }

    /** 已确认测试点中的断言作为 HUMAN 来源的预期；included 为 null 时保留全部。 */
    static List<Expectation> humanExpectations(TestPoint base, Set<String> included) {
        List<Expectation> result = new ArrayList<>();
        new TreeMap<>(base.assertions()).forEach((callId, list) -> {
            if (included != null && !included.contains(callId)) return;
            for (var a : list) result.add(new Expectation(callId, a, SourceType.HUMAN, "point/" + base.id() + "/" + a.id(), null, null));
        });
        return result;
    }

    private static TestIntent intent(TestPoint base, String sourceRule, String title, String category, List<String> covers, Variation variation,
                                     String end, List<String> suppressed, List<Expectation> expectations, Map<String, Map<String, Binding>> data,
                                     List<String> issues, String rationale) {
        var intent = new TestIntent(null, null, Strategy.RULE, sourceRule, title, category, covers, variation, end, suppressed,
                base.intentionallyViolatedRule() != null && "BASELINE".equals(category) ? base.intentionallyViolatedRule() : null,
                expectations, data, Map.of(), null, rationale, issues);
        return intent.withId(PlanGuard.intentId(intent));
    }

    private record Value(String rule, Object value, boolean negative) {}

    static boolean validScalar(Object value, Map<?, ?> schema) {
        if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(v -> canonical(v).equals(canonical(value)))) return false;
        if (value instanceof Number n) {
            BigDecimal number = new BigDecimal(n.toString());
            for (String key : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) if (schema.get(key) instanceof Number bound) {
                int comparison = number.compareTo(new BigDecimal(bound.toString()));
                if ((key.equals("minimum") && (comparison < 0 || comparison == 0 && Boolean.TRUE.equals(schema.get("exclusiveMinimum"))))
                        || (key.equals("maximum") && (comparison > 0 || comparison == 0 && Boolean.TRUE.equals(schema.get("exclusiveMaximum"))))
                        || key.equals("exclusiveMinimum") && comparison <= 0 || key.equals("exclusiveMaximum") && comparison >= 0) return false;
            }
            if (schema.get("multipleOf") instanceof Number step) {
                var multiple = new BigDecimal(step.toString());
                if (multiple.signum() <= 0 || number.remainder(multiple).signum() != 0) return false;
            }
        }
        if (value instanceof String s) {
            int length = s.codePointCount(0, s.length());
            if (schema.get("minLength") instanceof Number n && length < n.intValue() || schema.get("maxLength") instanceof Number m && length > m.intValue()) return false;
        }
        return true;
    }

    private static List<Value> variations(Map<?, ?> schema, String type) {
        List<Value> result = new ArrayList<>();
        if (schema.get("enum") instanceof List<?> values) values.stream().limit(100).forEach(value -> result.add(new Value("enum", value, false)));
        if (Set.of("integer", "number").contains(type)) for (String key : List.of("minimum", "maximum")) if (schema.get(key) instanceof Number number) {
            var value = new BigDecimal(number.toString());
            result.add(new Value(key, value, false));
            // An integer has a defined adjacent value; a decimal needs an explicit multipleOf step.
            BigDecimal delta = "integer".equals(type) ? BigDecimal.ONE : schema.get("multipleOf") instanceof Number step ? new BigDecimal(step.toString()) : null;
            if (delta != null && delta.signum() > 0) result.add(new Value(key + "Outside", key.equals("minimum") ? value.subtract(delta) : value.add(delta), true));
        }
        if ("string".equals(type)) for (String key : List.of("minLength", "maxLength")) if (schema.get(key) instanceof Number n && n.intValue() >= 0 && n.intValue() <= 8000) {
            int length = n.intValue();
            result.add(new Value(key, "x".repeat(length), false));
            int outside = key.equals("minLength") ? length - 1 : length + 1;
            if (outside >= 0 && outside <= 8000) result.add(new Value(key + "Outside", "x".repeat(outside), true));
        }
        return result;
    }
}
