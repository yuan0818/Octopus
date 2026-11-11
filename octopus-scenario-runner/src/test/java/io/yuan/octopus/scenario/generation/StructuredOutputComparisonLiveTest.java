package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.scenario.testing.FlowFailure;
import io.yuan.octopus.scenario.testing.FlowModelClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * SO-011：结构化输出的在线对照评估。<b>会产生真实的模型调用和费用</b>，只在显式设置环境变量时执行：
 * <pre>
 * OCTOPUS_COMPARE_MODELS=qwen-max,qwen-max-schema   # review-agent 中已配置的模型名（同一模型的不同输出模式）
 * OCTOPUS_REVIEW_URL=http://localhost:8083          # 可选，运行中的 review-agent
 * OCTOPUS_COMPARE_RUNS=5                            # 可选，每个模型的次数（1–50）
 * </pre>
 * 对合成的“登录 → 创建订单”流程，每次执行一次 Planner 首轮和一次需要模型的数据位填充，统计调用数与格式无效次数；
 * 结果打印到控制台并写入 build/reports/structured-output-comparison.md。未设置时跳过（AT-S8）。
 */
@EnabledIfEnvironmentVariable(named = "OCTOPUS_COMPARE_MODELS", matches = ".+")
class StructuredOutputComparisonLiveTest {

    static final class Counting extends FlowModelClient {
        int planCalls, materializeCalls, planInvalid, materializeInvalid;
        final Set<String> versions = new TreeSet<>();
        Counting(String url) { super(url); }
        @Override public VersionedResponse casePlan(String model, Map<String, Object> context, Duration timeout) {
            planCalls++;
            var response = super.casePlan(model, context, timeout);
            versions.add(response.promptVersion());
            return response;
        }
        @Override public VersionedResponse caseMaterialize(String model, Map<String, Object> context, Duration timeout) {
            materializeCalls++;
            var response = super.caseMaterialize(model, context, timeout);
            versions.add(response.promptVersion());
            return response;
        }
        @Override public void recordFormatInvalid(String kind, String model, String promptVersion) {
            if ("CASE_PLAN".equals(kind)) planInvalid++; else materializeInvalid++;
        }
    }

    record Row(String model, int runs, Counting counts, int planFailed, int intents, int intentsWithIssues,
               int fillFailed, int slotsFilled, int slotsAsked, List<String> errors) {}

    @Test
    void compareFormatCorrectionsAcrossModels() throws Exception {
        String url = Optional.ofNullable(System.getenv("OCTOPUS_REVIEW_URL")).filter(s -> !s.isBlank()).orElse("http://localhost:8083");
        int runs = Math.max(1, Math.min(50, Integer.parseInt(Optional.ofNullable(System.getenv("OCTOPUS_COMPARE_RUNS")).filter(s -> !s.isBlank()).orElse("5"))));
        var flow = OperatorDataReviewTest.flow();
        var ops = OperatorDataReviewTest.operations();
        var base = RuleIntentSource.plan(flow, GenerationFixtures.happy(), ops, null, Set.of()).intents().getFirst();
        var slotIntent = base.withMutable(base.dataPlan(), Map.of("create", Map.of(
                "coupon", new Slot("string", "VALID", Map.of("pattern", "^CP-[0-9]{4}$"), false),
                "count", new Slot("integer", "VIOLATING", Map.of("minimum", 1, "maximum", 5), false))), null, List.of());

        List<Row> rows = new ArrayList<>();
        for (String model : System.getenv("OCTOPUS_COMPARE_MODELS").split(",")) {
            model = model.strip();
            if (model.isEmpty()) continue;
            var client = new Counting(url);
            int planFailed = 0, intents = 0, withIssues = 0, fillFailed = 0, filled = 0, asked = 0;
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                try {
                    var plan = new ModelIntentSource(client).plan(new ModelIntentSource.Context(flow, "h", GenerationFixtures.happy(), ops,
                            null, 5, "对照评估第 " + (i + 1) + " 次", model, Set.of()));
                    intents += plan.intents().size();
                    withIssues += (int) plan.intents().stream().filter(t -> !t.issues().isEmpty()).count();
                } catch (FlowFailure failure) {
                    if (failure.code == FlowFailure.Code.MODEL_RESPONSE_INVALID) planFailed++; else errors.add("plan:" + failure.code);
                }
                try {
                    var fill = new DataSlotFiller(client, null).fill(slotIntent, flow, ops, model);
                    asked += 2;
                    filled += fill.bindings().getOrDefault("create", Map.of()).size();
                    if (fill.issues().stream().anyMatch(s -> s.contains("格式无效"))) fillFailed++;
                } catch (FlowFailure failure) {
                    errors.add("fill:" + failure.code);
                }
            }
            rows.add(new Row(model, runs, client, planFailed, intents, withIssues, fillFailed, filled, asked, errors));
        }

        var report = new StringBuilder("# 结构化输出对照评估\n\n")
                .append("| 模型 | 提示版本 | 次数 | Planner 调用 | Planner 格式无效 | 修正率 | Planner 最终失败 | 意图 | 带问题的意图 | Operator 调用 | Operator 格式无效 | 修正率 | Operator 最终失败 | 数据位通过 | 其他错误 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (var r : rows) {
            var c = r.counts();
            report.append("| ").append(r.model()).append(" | ").append(String.join("<br>", c.versions)).append(" | ").append(r.runs())
                    .append(" | ").append(c.planCalls).append(" | ").append(c.planInvalid).append(" | ").append(rate(c.planInvalid, c.planCalls))
                    .append(" | ").append(r.planFailed()).append(" | ").append(r.intents()).append(" | ").append(r.intentsWithIssues())
                    .append(" | ").append(c.materializeCalls).append(" | ").append(c.materializeInvalid).append(" | ").append(rate(c.materializeInvalid, c.materializeCalls))
                    .append(" | ").append(r.fillFailed()).append(" | ").append(r.slotsFilled()).append("/").append(r.slotsAsked())
                    .append(" | ").append(r.errors().isEmpty() ? "" : String.join(", ", r.errors())).append(" |\n");
        }
        report.append("\n修正率 = 格式无效次数 ÷ 调用数。数据位通过 = 通过类型与契约约束校验的数据位数 ÷ 请求填充的数据位数。\n");
        System.out.println(report);
        Path out = Path.of("build", "reports", "structured-output-comparison.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    static String rate(int part, int total) {
        return total == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / total);
    }
}
