package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Operator 的确定性物化（设计 8.2）：意图 → 候选测试点。
 * 断言逐条拷贝自意图的预期，不经过模型（OPER-004）；预期为 MISSING 的断言保留为空，候选随后被判 BLOCKED。
 */
public final class IntentMaterializer {
    private IntentMaterializer() {}

    public record Materialized(TestPoint point, String fingerprint, List<String> issues) {}

    /**
     * @param fills    Operator 为 slots 填好的绑定（callId → input → binding），可为空
     * @param observed 本任务已观测到的运行态值的 SHA-256（OPER-003）
     */
    public static Materialized materialize(TestIntent intent, String flowHash, String basePointId, String taskId, int round, String model,
                                           Map<String, Map<String, Binding>> fills, Set<String> observed) {
        List<String> issues = new ArrayList<>();
        Map<String, Map<String, Binding>> overrides = new TreeMap<>();
        intent.dataPlan().forEach((call, inputs) -> overrides.put(call, new TreeMap<>(inputs)));
        if (fills != null) fills.forEach((call, inputs) -> overrides.computeIfAbsent(call, k -> new TreeMap<>()).putAll(inputs));
        if (intent.variation() != null)
            overrides.computeIfAbsent(intent.variation().callId(), k -> new TreeMap<>()).putAll(intent.variation().inputs());
        for (var call : intent.slots().entrySet()) for (var input : call.getValue().keySet())
            if (!overrides.getOrDefault(call.getKey(), Map.of()).containsKey(input)) issues.add("数据位未填充：" + call.getKey() + "." + input);
        // OPER-003：探测中观察到的业务 ID、Token、时间戳不能被写成用例字面量
        for (var call : overrides.entrySet()) for (var input : call.getValue().entrySet()) {
            var binding = input.getValue();
            if ("literal".equals(binding.source()) && observed.contains(LeakCheck.digest(binding.value())))
                issues.add("LEAK:" + call.getKey() + "." + input.getKey() + " 使用了探测运行中产生的值，请改用流程内提取或数据工厂");
        }
        Map<String, List<Assertion>> assertions = new TreeMap<>();
        Map<String, ExpectationSource> sources = new TreeMap<>();
        for (var e : intent.expectations()) {
            var list = assertions.computeIfAbsent(e.callId(), k -> new ArrayList<>());
            if (e.sourceType() == SourceType.MISSING) {
                String label = e.assertion().id() + (e.assertion().path() == null ? "" : "（" + e.assertion().path() + "）");
                issues.add("预期 " + label + " 缺少依据：" + (e.note() == null ? "请补充业务依据" : e.note())
                        + (e.proposed() == null ? "" : "；建议值 " + canonical(e.proposed()) + " 需人工确认"));
                continue;
            }
            list.add(e.assertion());
            sources.put(e.callId() + "/" + e.assertion().id(), new ExpectationSource(e.sourceType().name(), e.sourceRef(), null));
        }
        Map<String, List<Assertion>> frozen = new LinkedHashMap<>();
        assertions.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        Map<String, Map<String, Binding>> frozenOverrides = new LinkedHashMap<>();
        overrides.forEach((k, v) -> frozenOverrides.put(k, new LinkedHashMap<>(v)));

        var identity = new LinkedHashMap<String, Object>();
        identity.put("overrides", frozenOverrides);
        identity.put("assertions", frozen);
        identity.put("end", intent.endAtStepRef());
        identity.put("suppressed", intent.suppressedOutputCalls());
        if (intent.source() == Strategy.MODEL) identity.put("rule", intent.intentionallyViolatedRule());
        String fingerprint = hash(identity);
        String id = "case_" + hash(List.of(intent.intentId(), fingerprint)).substring(0, 32);
        String sourceRule = intent.source() == Strategy.MODEL ? "LLM:" + (model == null || model.isBlank() ? "default" : model) : intent.sourceRule();
        var generation = new CaseGeneration(flowHash, sourceRule, fingerprint, basePointId, taskId, intent.intentId(), round,
                sources.isEmpty() ? null : sources, null, null);
        var point = new TestPoint(id, intent.title(), intent.targetCallIds(), frozen, frozenOverrides, intent.intentionallyViolatedRule(),
                intent.endAtStepRef(), intent.suppressedOutputCalls(), generation);
        return new Materialized(point, fingerprint, issues);
    }

    /** 与 assert.groovy 探测证据一致的值规范化与摘要。 */
    public static final class LeakCheck {
        private LeakCheck() {}
        public static String normalize(Object value) {
            if (value == null) return "null";
            if (value instanceof String s) return s;
            if (value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            if (value instanceof Boolean b) return b.toString();
            return canonical(value);
        }
        public static String digest(Object value) {
            try {
                var bytes = java.security.MessageDigest.getInstance("SHA-256").digest(normalize(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(bytes);
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }
}
