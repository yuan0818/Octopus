package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 1E：LLM 建议质量度量。
 * <ul>
 *   <li>{@link #record}：模型调用（GENERATED / FAILED，含耗时与失败类别）与人工结果（ACCEPTED / REJECTED / EDITED）。
 *       写入失败只记日志，不影响业务流程。</li>
 *   <li>{@link #summary}：按类型与模型汇总调用量、失败率、采纳率、P50/P95 耗时与主要失败类别。</li>
 *   <li>{@link #evaluate}：黄金流程离线评估——把模型补全后的 Flow 与人工确认的黄金 Flow 逐项比较，
 *       给出 inputs / outputs / assertions 的 precision / recall / F1，用于换模型或改 prompt 前后的回归对比。</li>
 * </ul>
 */
@Service
public class SuggestionMetricsService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionMetricsService.class);
    static final Set<String> OUTCOMES = Set.of("GENERATED", "FAILED", "ACCEPTED", "REJECTED", "EDITED");

    public record Feedback(String kind, String outcome, String refId, String model, Integer changedItems) {}

    public record Row(String kind, String model, long generated, long failed, long accepted, long rejected, long edited,
                      Double failureRate, Double acceptanceRate, Long p50LatencyMs, Long p95LatencyMs,
                      Map<String, Long> failureCodes) {}

    public record Summary(String since, long events, List<Row> rows) {}

    public record Score(int expected, int produced, int matched, double precision, double recall, double f1,
                        List<String> missing, List<String> unexpected) {}

    public record Evaluation(Score inputs, Score outputs, Score assertions, double overallF1) {}

    private final SuggestionEventRepository events;

    public SuggestionMetricsService(SuggestionEventRepository events) {
        this.events = events;
    }

    public void record(String kind, String outcome, String model, String refId, Long latencyMs,
                       Integer responseChars, Integer changedItems, String failureCode) {
        try {
            SuggestionEvent event = new SuggestionEvent();
            event.id = UUID.randomUUID().toString();
            event.kind = truncate(kind, 32);
            event.outcome = outcome;
            event.model = truncate(model == null || model.isBlank() ? null : model, 128);
            event.refId = truncate(refId, 128);
            event.latencyMs = latencyMs;
            event.responseChars = responseChars;
            event.changedItems = changedItems;
            event.failureCode = truncate(failureCode, 48);
            event.createdAt = Instant.now();
            events.save(event);
        } catch (RuntimeException e) {
            log.debug("Suggestion metric not recorded ({} {}): {}", kind, outcome, e.getMessage());
        }
    }

    public void feedback(Feedback feedback) {
        require(feedback != null && feedback.kind() != null && feedback.kind().matches("[A-Z_]{2,32}"), "kind 无效");
        require(Set.of("ACCEPTED", "REJECTED", "EDITED").contains(feedback.outcome()), "outcome 只支持 ACCEPTED / REJECTED / EDITED");
        require(feedback.changedItems() == null || (feedback.changedItems() >= 0 && feedback.changedItems() <= 10_000), "changedItems 无效");
        record(feedback.kind(), feedback.outcome(), feedback.model(), feedback.refId(), null, null, feedback.changedItems(), null);
    }

    public Summary summary(int days) {
        require(days >= 1 && days <= 365, "days 需要 1..365");
        Instant since = Instant.now().minus(Duration.ofDays(days));
        return summarize(events.findTop50000ByCreatedAtAfterOrderByCreatedAtDesc(since), since);
    }

    static Summary summarize(List<SuggestionEvent> all, Instant since) {
        Map<String, List<SuggestionEvent>> groups = all.stream().collect(Collectors.groupingBy(
                e -> e.kind + "\u0000" + (e.model == null ? "" : e.model), TreeMap::new, Collectors.toList()));
        List<Row> rows = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            String[] key = entry.getKey().split("\u0000", -1);
            List<SuggestionEvent> list = entry.getValue();
            long generated = count(list, "GENERATED"), failed = count(list, "FAILED"), accepted = count(list, "ACCEPTED"),
                    rejected = count(list, "REJECTED"), edited = count(list, "EDITED");
            List<Long> latencies = list.stream().filter(e -> e.latencyMs != null && ("GENERATED".equals(e.outcome) || "FAILED".equals(e.outcome)))
                    .map(e -> e.latencyMs).sorted().toList();
            Map<String, Long> codes = list.stream().filter(e -> e.failureCode != null)
                    .collect(Collectors.groupingBy(e -> e.failureCode, TreeMap::new, Collectors.counting()));
            long calls = generated + failed;
            long decided = accepted + rejected + edited;
            rows.add(new Row(key[0], key[1].isEmpty() ? null : key[1], generated, failed, accepted, rejected, edited,
                    calls == 0 ? null : round((double) failed / calls),
                    // 采纳率按"有人工结论的建议"计算：EDITED 视为部分采纳，不计入 accepted
                    decided == 0 ? null : round((double) accepted / decided),
                    percentile(latencies, 0.50), percentile(latencies, 0.95), codes));
        }
        return new Summary(since.toString(), all.size(), rows);
    }

    // ------------------------------------------------------------------ golden evaluation

    public Evaluation evaluate(Flow golden, Flow candidate) {
        require(golden != null && candidate != null, "需要 golden 与 candidate 两个 Flow");
        Set<String> gi = new TreeSet<>(), ci = new TreeSet<>(), go = new TreeSet<>(), co = new TreeSet<>(), ga = new TreeSet<>(), ca = new TreeSet<>();
        collect(golden, gi, go, ga);
        collect(candidate, ci, co, ca);
        Score inputs = score(gi, ci), outputs = score(go, co), assertions = score(ga, ca);
        int expected = gi.size() + go.size() + ga.size();
        int produced = ci.size() + co.size() + ca.size();
        int matched = inputs.matched() + outputs.matched() + assertions.matched();
        double precision = produced == 0 ? (expected == 0 ? 1 : 0) : (double) matched / produced;
        double recall = expected == 0 ? 1 : (double) matched / expected;
        return new Evaluation(inputs, outputs, assertions, round(f1(precision, recall)));
    }

    /**
     * 比较键刻意忽略输出 key 的命名与断言 id（模型可以自由命名），只比较语义：
     * 输入 = 节点 + 参数 + 绑定来源与值；输出 = 节点 + 回应路径 + 类型；断言 = 测试点 + 节点 + 类型 + 路径 + 预期值。
     * 回应路径统一去掉 "$.body" 前缀（LLM 约定与人工写法等价）。
     */
    private static void collect(Flow flow, Set<String> inputs, Set<String> outputs, Set<String> assertions) {
        for (FlowNode node : flow.nodes()) {
            node.inputs().forEach((key, b) -> inputs.add(node.id() + "." + key + "=" + b.source() + ":" + TestingJson.canonical(b.value())));
            node.outputs().forEach(o -> outputs.add(node.id() + "." + normalizePath(o.path()) + ":" + o.type()));
        }
        for (TestPoint point : flow.testPoints()) {
            point.assertions().forEach((nodeId, list) -> list.forEach(a -> assertions.add(point.id() + "/" + nodeId + "/" + a.kind()
                    + ":" + normalizePath(a.path()) + "=" + (a.expected() == null ? "null" : TestingJson.canonical(a.expected())))));
        }
    }

    static String normalizePath(String path) {
        if (path == null) return "";
        if (path.equals("$.body")) return "$";
        if (path.startsWith("$.body.") || path.startsWith("$.body[")) return "$" + path.substring("$.body".length());
        return path;
    }

    private static Score score(Set<String> expected, Set<String> produced) {
        Set<String> matched = new TreeSet<>(expected);
        matched.retainAll(produced);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(produced);
        Set<String> unexpected = new TreeSet<>(produced);
        unexpected.removeAll(expected);
        double precision = produced.isEmpty() ? (expected.isEmpty() ? 1 : 0) : (double) matched.size() / produced.size();
        double recall = expected.isEmpty() ? 1 : (double) matched.size() / expected.size();
        return new Score(expected.size(), produced.size(), matched.size(), round(precision), round(recall), round(f1(precision, recall)),
                missing.stream().limit(50).toList(), unexpected.stream().limit(50).toList());
    }

    // ------------------------------------------------------------------ helpers

    /** 人工结论按 refId 去重（确认接口可安全重放，重放不应重复计数）；模型调用逐次计数。 */
    private static long count(List<SuggestionEvent> list, String outcome) {
        if (Set.of("ACCEPTED", "REJECTED", "EDITED").contains(outcome)) {
            Set<String> refs = new HashSet<>();
            long anonymous = 0;
            for (SuggestionEvent e : list) {
                if (!outcome.equals(e.outcome)) continue;
                if (e.refId == null) anonymous++; else refs.add(e.refId);
            }
            return refs.size() + anonymous;
        }
        return list.stream().filter(e -> outcome.equals(e.outcome)).count();
    }

    static Long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return null;
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double f1(double precision, double recall) {
        return precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
