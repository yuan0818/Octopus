package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Phase 1D：对 FAIL / INCONCLUSIVE 运行做 LLM 建议性分析。
 * <p>
 * 边界（与流程补全一致）：
 * <ul>
 *   <li><b>只作建议</b>：结果单独保存在 {@code business_run_triage}，从不修改运行的 state / verdict。</li>
 *   <li><b>最小输入</b>：只发送采样摘要（标签、状态码、耗时、断言 id / Schema 违规路径）、已确认的断言定义、
 *       步骤绑定的来源类型（不含字面值）与变更证据；不发送请求/响应正文、初始上下文实际值或凭据。</li>
 *   <li><b>严格输出</b>：模型输出必须符合固定 JSON 结构，引用的 stepId / assertionId 必须真实存在，否则拒绝并不保存。</li>
 *   <li><b>确定性信号先行</b>：平台先按规则给出信号（基础设施错误、非 HTTP 状态码、5xx、被阻断步骤等）一并提供给模型和用户。</li>
 * </ul>
 */
@Service
public class RunTriageService {

    public static final Set<String> CATEGORIES = Set.of(
            "PRODUCT_DEFECT", "TEST_DATA", "ENVIRONMENT", "ORACLE_OUTDATED", "TEST_DEFINITION", "FLAKY", "UNKNOWN");
    public static final Set<String> CONFIDENCE = Set.of("LOW", "MEDIUM", "HIGH");
    private static final int MAX_EVIDENCE_CHARS = 30_000;

    public record FailedCheck(String stepId, String assertionId, String explanation) {}

    public record Triage(String category, String confidence, String summary, List<FailedCheck> failedChecks,
                         List<String> evidence, List<String> suggestedActions, List<String> questions) {}

    public record View(String runId, Triage triage, List<String> deterministicSignals, String model, String createdAt, boolean advisory) {}

    private final BusinessTestRunRepository runs;
    private final BusinessTestScenarioRepository scenarios;
    private final BusinessKnowledgeStore knowledge;
    private final FlowModelClient models;
    private final RunTriageRepository triages;
    private final SuggestionMetricsService metrics;

    public RunTriageService(BusinessTestRunRepository runs, BusinessTestScenarioRepository scenarios,
                            BusinessKnowledgeStore knowledge, FlowModelClient models, RunTriageRepository triages,
                            SuggestionMetricsService metrics) {
        this.runs = runs;
        this.scenarios = scenarios;
        this.knowledge = knowledge;
        this.models = models;
        this.triages = triages;
        this.metrics = metrics;
    }

    public Optional<View> get(String runId) {
        return triages.findById(runId).map(t -> {
            var run = runs.findById(runId).orElse(null);
            return new View(runId, read(t.triageJson, Triage.class), run == null ? List.of() : signals(run, report(run)),
                    t.model, t.createdAt.toString(), true);
        });
    }

    public View triage(String runId, String model) {
        BusinessTestRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("Unknown run"));
        require(!Set.of("QUEUED", "RUNNING").contains(run.state), "运行尚未结束");
        require(Set.of("FAIL", "INCONCLUSIVE").contains(run.verdict), "只分析 FAIL / INCONCLUSIVE 的运行");
        var scenario = scenarios.findById(run.scenarioId).orElseThrow(() -> new IllegalArgumentException("Unknown scenario"));
        TestIR ir = read(scenario.irJson, TestIR.class);
        JmeterProcessExecutor.Result report = report(run);
        List<String> signals = signals(run, report);

        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> runInfo = new LinkedHashMap<>();
        runInfo.put("id", run.id);
        runInfo.put("state", run.state);
        runInfo.put("verdict", run.verdict);
        runInfo.put("error", run.error);
        if (report != null) {
            runInfo.put("executionComplete", report.executionComplete());
            runInfo.put("cleanupStatus", report.cleanupStatus());
        }
        context.put("run", runInfo);
        context.put("steps", steps(ir));
        context.put("samples", report == null ? List.of() : report.samples().stream().limit(500).toList());
        context.put("calls", report == null ? List.of() : report.calls());
        context.put("deterministicSignals", signals);
        context.put("changeEvidence", evidence(ir));

        long started = System.nanoTime();
        FlowModelClient.Response response;
        try {
            response = models.runTriage(model, context);
        } catch (RuntimeException e) {
            metrics.record("RUN_TRIAGE", "FAILED", model, runId, elapsedMs(started), null, null, failureCode(e));
            throw e;
        }
        Triage triage;
        try {
            triage = parse(response.content(), ir);
        } catch (IllegalArgumentException e) {
            metrics.record("RUN_TRIAGE", "FAILED", response.model(), runId, elapsedMs(started),
                    response.content() == null ? 0 : response.content().length(), null, "MODEL_RESPONSE_INVALID");
            throw new IllegalArgumentException("模型输出不符合失败分析格式，未保存：" + e.getMessage());
        }
        metrics.record("RUN_TRIAGE", "GENERATED", response.model(), runId, elapsedMs(started),
                response.content().length(), triage.failedChecks().size(), null);

        RunTriage entity = triages.findById(runId).orElseGet(RunTriage::new);
        entity.runId = runId;
        entity.category = triage.category();
        entity.confidence = triage.confidence();
        entity.triageJson = canonical(triage);
        entity.model = response.model();
        entity.createdAt = Instant.now();
        triages.save(entity);
        return new View(runId, triage, signals, response.model(), entity.createdAt.toString(), true);
    }

    // ------------------------------------------------------------------ context

    private static JmeterProcessExecutor.Result report(BusinessTestRun run) {
        if (run.reportJson == null || run.reportJson.isBlank()) return null;
        try {
            return GSON.fromJson(run.reportJson, JmeterProcessExecutor.Result.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 步骤与断言定义；输入只给出来源类型，不给字面值。 */
    static List<Map<String, Object>> steps(TestIR ir) {
        Map<String, Operation> operations = new HashMap<>();
        ir.operations().forEach(o -> operations.put(o.ref(), o));
        List<Step> all = new ArrayList<>(ir.steps());
        if (ir.cleanup() != null) ir.cleanup().forEach(c -> all.add(c.call()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Step step : all) {
            Operation operation = operations.get(step.operationRef());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", step.id());
            if (operation != null) {
                row.put("operation", Map.of("ref", operation.ref(), "method", operation.method(), "path", operation.path()));
            }
            Map<String, String> inputs = new TreeMap<>();
            step.inputs().forEach((k, b) -> inputs.put(k, "context".equals(b.source()) ? "context:" + b.value() : b.source()));
            row.put("inputs", inputs);
            row.put("outputs", step.outputs().stream().map(o -> Map.of("key", o.key(), "path", o.path(), "type", o.type())).toList());
            row.put("assertions", step.assertions().stream().map(RunTriageService::assertionView).toList());
            row.put("waitMillis", step.waitMillis());
            row.put("stateChecks", step.states().size());
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> assertionView(Assertion a) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", a.id());
        view.put("kind", a.kind());
        view.put("path", a.path());
        if (DatastoreChecks.KIND.equals(a.kind())) view.put("expected", "<datastore verification plan>");
        else if (ResponseSchemaChecks.KIND.equals(a.kind())) view.put("expected", a.expected() == null ? "<contract schema>" : "<explicit schema>");
        else {
            String expected = canonical(a.expected());
            view.put("expected", expected.length() > 300 ? expected.substring(0, 300) + "…" : expected);
        }
        return view;
    }

    private String evidence(TestIR ir) {
        if (ir.changeEvidenceRefs() == null || ir.changeEvidenceRefs().isEmpty()) return "";
        try {
            String json = canonical(knowledge.evidenceSnapshots(ir.changeEvidenceRefs()));
            return json.length() > MAX_EVIDENCE_CHARS ? json.substring(0, MAX_EVIDENCE_CHARS) + "…(truncated)" : json;
        } catch (RuntimeException e) {
            return "<evidence unavailable>";
        }
    }

    /** 规则信号：不依赖模型，直接展示给用户。 */
    static List<String> signals(BusinessTestRun run, JmeterProcessExecutor.Result report) {
        List<String> signals = new ArrayList<>();
        if (run.error != null) {
            if (run.error.startsWith("Execution infrastructure failure") || run.error.startsWith("JMeter")
                    || run.error.contains("queue")) signals.add("INFRASTRUCTURE: " + run.error);
            else signals.add("RUN_ERROR: " + run.error);
        }
        if (!"COMPLETED".equals(run.state)) signals.add("RUN_STATE: " + run.state);
        if (report == null) {
            signals.add("NO_REPORT: 运行没有可读的采样报告");
            return signals;
        }
        if (!report.executionComplete()) signals.add("INCOMPLETE: 必需的步骤没有全部执行");
        for (var sample : report.samples()) {
            if (sample.success()) continue;
            String code = sample.responseCode() == null ? "" : sample.responseCode();
            if (!code.matches("[1-5][0-9]{2}")) signals.add("NON_HTTP_STATUS: " + sample.label() + " → " + code);
            else if (code.startsWith("5")) signals.add("SERVER_ERROR: " + sample.label() + " → " + code);
            else if (sample.failure() != null && sample.failure().contains("| schema:")) signals.add("SCHEMA_VIOLATION: " + sample.label());
            if (signals.size() >= 30) break;
        }
        if (report.calls() != null) report.calls().stream().filter(c -> "BLOCKED".equals(c.status())).limit(10)
                .forEach(c -> signals.add("BLOCKED: " + c.callId()));
        if ("FAIL".equals(report.cleanupStatus())) signals.add("CLEANUP_FAILED: 清理步骤失败，后续运行可能受残留数据影响");
        return signals;
    }

    // ------------------------------------------------------------------ output validation

    static Triage parse(String content, TestIR ir) {
        require(content != null && !content.isBlank(), "空响应");
        String json = content.strip();
        if (json.startsWith("```")) {
            int first = json.indexOf('\n'), last = json.lastIndexOf("```");
            require(first > 0 && last > first, "无法解析围栏");
            json = json.substring(first + 1, last).strip();
        }
        require(json.length() <= 60_000, "输出过大");
        Triage t;
        try {
            t = read(json, Triage.class);
        } catch (com.google.gson.JsonParseException e) {
            throw new IllegalArgumentException("JSON 语法错误");
        }
        require(t != null && CATEGORIES.contains(t.category()), "category 无效");
        require(CONFIDENCE.contains(t.confidence()), "confidence 无效");
        require(t.summary() != null && !t.summary().isBlank() && t.summary().length() <= 1500, "summary 无效");
        List<FailedCheck> checks = t.failedChecks() == null ? List.of() : t.failedChecks();
        require(checks.size() <= 30, "failedChecks 过多");
        Map<String, Set<String>> assertionsByStep = new HashMap<>();
        List<Step> all = new ArrayList<>(ir.steps());
        if (ir.cleanup() != null) ir.cleanup().forEach(c -> all.add(c.call()));
        all.forEach(s -> assertionsByStep.put(s.id(), new HashSet<>(s.assertions().stream().map(Assertion::id).toList())));
        for (FailedCheck check : checks) {
            require(check != null && assertionsByStep.containsKey(check.stepId()), "failedChecks 引用了不存在的步骤");
            require(check.assertionId() == null || assertionsByStep.get(check.stepId()).contains(check.assertionId()),
                    "failedChecks 引用了不存在的断言");
            require(check.explanation() != null && check.explanation().length() <= 600, "explanation 无效");
        }
        List<String> evidence = boundedList(t.evidence(), 15, 400, "evidence");
        List<String> actions = boundedList(t.suggestedActions(), 8, 400, "suggestedActions");
        List<String> questions = boundedList(t.questions(), 8, 400, "questions");
        return new Triage(t.category(), t.confidence(), t.summary().strip(), List.copyOf(checks), evidence, actions, questions);
    }

    private static List<String> boundedList(List<String> values, int max, int maxChars, String field) {
        if (values == null) return List.of();
        require(values.size() <= max, field + " 过多");
        values.forEach(v -> require(v != null && v.length() <= maxChars, field + " 条目无效"));
        return List.copyOf(values);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String failureCode(RuntimeException e) {
        return e instanceof FlowFailure f && f.code != null ? f.code.name() : e.getClass().getSimpleName();
    }
}
