package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

import io.yuan.octopus.scenario.testing.JmeterProcessExecutor;
import java.util.*;

/**
 * Observer 的确定性分类（OBS-001、设计 5.4）：同一输入得出同一结果，可复算。
 * 不修改候选或预期；模型解释（若有）只能另行标注为“推测”（ROLE-003）。
 */
public final class ObservationClassifier {
    private ObservationClassifier() {}

    /** 探测结果。mayHaveSentRequests=false 表示确定没有发出业务请求（例如排队中被中断）。 */
    public record ProbeOutcome(String runId, String state, boolean mayHaveSentRequests, JmeterProcessExecutor.Result report,
                               List<Map<String, Object>> evidence) {}

    static final Set<String> SAFE_METHODS = StaticChecks.SAFE_METHODS;
    static final Set<String> UNOBSERVABLE_VERIFY = Set.of("VERIFY_UNAVAILABLE", "VERIFY_TIMEOUT", "VERIFY_ERROR", "VERIFY_IDENTITY", "VERIFY_PROBLEM", "VERIFY_INTERRUPTED", "VERIFY_FAILED");
    static final Set<String> ENVIRONMENT_PREPARE = Set.of("PREPARE_CREDENTIAL_MISSING", "PREPARE_TOOL_UNAVAILABLE", "PREPARE_ALGORITHM_UNSUPPORTED", "PREPARE_PUBLIC_KEY_INVALID", "PREPARE_CRYPTO_FAILED");
    static final String FAILURE_PREFIX = "Business verification failed: ";

    // ------------------------------------------------------------------ static

    public static Observation observeStatic(TestIntent intent, CandidateDraft draft, int round) {
        List<FeedbackItem> feedback = new ArrayList<>(missing(intent));
        Conclusion conclusion = Conclusion.NOT_PROBED;
        if (!draft.staticResult().ok()) {
            conclusion = Conclusion.STATIC_INVALID;
            for (String error : draft.staticResult().errors()) {
                var type = draft.staticResult().materializationError() ? FeedbackType.MATERIALIZATION_ERROR : FeedbackType.DEFINITION_INVALID;
                feedback.add(item(type, intent.intentId(), null, null, null, null, code(error), error));
            }
        }
        List<AssertionResult> assertions = intent.expectations().stream()
                .map(e -> new AssertionResult(e.callId(), e.assertion().id(), e.sourceType() == SourceType.MISSING ? AssertionClass.NO_EXPECTATION : AssertionClass.NOT_REACHED, e.sourceType()))
                .toList();
        return new Observation(intent.intentId(), round, "STATIC", null, conclusion, assertions, List.of(), dedupe(feedback), List.of(), null);
    }

    // ------------------------------------------------------------------ probe

    public static Observation observeProbe(TestIntent intent, CandidateDraft draft, TestIR ir, ProbeOutcome outcome, int round) {
        List<FeedbackItem> feedback = new ArrayList<>(missing(intent));
        if (!outcome.mayHaveSentRequests()) {
            feedback.add(item(FeedbackType.UNOBSERVABLE, intent.intentId(), null, null, null, null, "PROBE_NOT_STARTED", "探测未开始执行，未调用被测接口"));
            var base = observeStatic(intent, draft, round);
            return new Observation(intent.intentId(), round, "PROBE", outcome.runId(), Conclusion.NOT_PROBED, base.assertions(), List.of(), dedupe(feedback), List.of(), null);
        }
        var report = outcome.report();
        Map<String, JmeterProcessExecutor.Sample> samples = new LinkedHashMap<>();
        List<JmeterProcessExecutor.Sample> all = report == null || report.samples() == null ? List.of() : report.samples();
        for (var s : all) samples.put(s.label(), s);   // 轮询时同一标签有多条，最后一条为最终判定
        Map<String, String> callStatus = new HashMap<>();
        if (report != null && report.calls() != null) report.calls().forEach(c -> callStatus.put(c.callId(), c.status()));
        Map<String, Operation> operations = new HashMap<>();
        ir.operations().forEach(o -> operations.put(o.ref(), o));
        Set<String> targets = new HashSet<>(intent.targetCallIds());
        boolean aborted = !"COMPLETED".equals(outcome.state());
        Map<String, Map<String, Object>> actuals = actuals(outcome.evidence());
        Map<String, List<Expectation>> byCall = new LinkedHashMap<>();
        intent.expectations().forEach(e -> byCall.computeIfAbsent(e.callId(), k -> new ArrayList<>()).add(e));

        List<CallResult> calls = new ArrayList<>();
        List<AssertionResult> assertions = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        boolean contradicted = false, precondition = false, unknown = false, inconclusive = aborted, dataInvalid = false;
        boolean firstMissingSeen = false;
        for (var step : ir.steps()) {
            String id = step.id();
            var operation = operations.get(step.operationRef());
            boolean write = operation != null && !SAFE_METHODS.contains(String.valueOf(operation.method()).toUpperCase(Locale.ROOT));
            var sample = samples.get("step:" + id);
            var guard = samples.get("guard:" + id);
            var prepareFailure = all.stream().filter(s -> s.label().startsWith("prepare:" + id + ":") && !s.success()).reduce((a, b) -> b).orElse(null);
            String status = callStatus.getOrDefault(id, sample == null ? "NOT_RUN" : sample.success() ? "PASS" : "FAIL");
            String http = sample != null && numeric(sample.responseCode()) ? sample.responseCode() : null;
            String platform = prepareFailure != null ? prepareFailure.responseCode()
                    : guard != null && !guard.success() ? guard.responseCode()
                    : sample != null && http == null ? "TRANSPORT_ERROR" : null;
            boolean outcomeUnknown = false;
            if (write && sample != null && http == null) outcomeUnknown = true;            // 已发出但没有可信响应
            if (write && sample == null && aborted && !firstMissingSeen && !"BLOCKED".equals(status) && !"NOT_APPLICABLE".equals(status)
                    && prepareFailure == null && (guard == null || guard.success())) outcomeUnknown = true;   // 进程中止时可能在途
            if (sample == null && !firstMissingSeen) firstMissingSeen = true;
            boolean target = targets.contains(id);
            calls.add(new CallResult(id, status, http, target, write, outcomeUnknown, platform));
            unknown |= outcomeUnknown;

            Set<String> failed = failedIds(sample);
            boolean decode = failed.contains("response_decode_or_context");
            boolean callFailed = sample != null && !sample.success() || prepareFailure != null || guard != null && !guard.success();
            var callEvidence = new LinkedHashMap<String, Object>();
            callEvidence.put("callId", id);
            callEvidence.put("httpStatus", http);
            callEvidence.put("platformCode", platform);
            callEvidence.put("status", status);
            List<Map<String, Object>> assertionEvidence = new ArrayList<>();
            for (var e : byCall.getOrDefault(id, List.of())) {
                var a = e.assertion();
                AssertionClass cls;
                if (e.sourceType() == SourceType.MISSING) cls = AssertionClass.NO_EXPECTATION;
                else if ("DATASTORE_VERIFY".equals(a.kind())) {
                    var check = Optional.ofNullable(samples.get("verify:" + id + ":" + a.id())).orElse(samples.get("observe:" + id + ":" + a.id()));
                    if (check == null) cls = AssertionClass.NOT_REACHED;
                    else if (check.success()) cls = AssertionClass.MATCHED;
                    else if ("VERIFY_MISMATCH".equals(check.responseCode())) cls = AssertionClass.CONTRADICTED;
                    else cls = AssertionClass.UNOBSERVABLE;
                } else if (sample == null) cls = AssertionClass.NOT_REACHED;
                else if (http == null || decode) cls = AssertionClass.UNOBSERVABLE;
                else if (failed.contains(a.id())) cls = AssertionClass.CONTRADICTED;
                else cls = AssertionClass.MATCHED;
                assertions.add(new AssertionResult(id, a.id(), cls, e.sourceType()));
                var row = new LinkedHashMap<String, Object>();
                row.put("assertionId", a.id());
                row.put("kind", a.kind());
                row.put("path", a.path());
                row.put("expected", a.expected());
                row.put("source", e.sourceType().name());
                row.put("classification", cls.name());
                if (actuals.containsKey(id) && actuals.get(id).containsKey(a.id())) row.put("actual", actuals.get(id).get(a.id()));
                assertionEvidence.add(row);
                if (!target) continue;   // 前置调用的断言失败按前置失败处理（ADR-CG-08）
                switch (cls) {
                    case CONTRADICTED -> {
                        boolean slotData = !intent.slots().getOrDefault(id, Map.of()).isEmpty() && (intent.variation() == null || !intent.variation().negative());
                        if (slotData && "STATUS_EQUALS".equals(a.kind()) && http != null && http.startsWith("4")) {
                            dataInvalid = true;
                            feedback.add(item(FeedbackType.DATA_INVALID, intent.intentId(), stepOf(ir, id), id, a.id(), http, null, "正向意图的数据被目标接口拒绝"));
                        } else {
                            contradicted = true;
                            feedback.add(item(FeedbackType.CONTRACT_MISMATCH, intent.intentId(), stepOf(ir, id), id, a.id(), http, check(a), "实际行为与预期依据不符：疑似缺陷或依据错误"));
                        }
                    }
                    case UNOBSERVABLE -> {
                        inconclusive = true;
                        feedback.add(item(FeedbackType.UNOBSERVABLE, intent.intentId(), stepOf(ir, id), id, a.id(), http, platform == null ? "VERIFY_UNOBSERVABLE" : platform, "无法观测该断言"));
                    }
                    case NOT_REACHED -> inconclusive = true;
                    default -> {}
                }
            }
            callEvidence.put("assertions", assertionEvidence);
            evidence.add(callEvidence);
            // 提取或状态约束失败属于流程级定义
            for (String token : failed) if (token.startsWith("output:") || token.startsWith("state:")) {
                if (target) contradicted = true;
                feedback.add(item(target ? FeedbackType.FLOW_DEFINITION_MISMATCH : FeedbackType.PRECONDITION_FAILED, intent.intentId(), stepOf(ir, id), id,
                        token, http, token.startsWith("output:") ? "OUTPUT_EXTRACTION" : "STATE_CONSTRAINT", "流程提取或状态约束与实际响应不符"));
                if (!target) precondition = true;
            }
            if (!target && callFailed && failed.stream().noneMatch(t -> t.startsWith("output:") || t.startsWith("state:"))) {
                if (prepareFailure != null && ENVIRONMENT_PREPARE.contains(prepareFailure.responseCode())) {
                    inconclusive = true;
                    feedback.add(item(FeedbackType.UNOBSERVABLE, intent.intentId(), stepOf(ir, id), id, null, http, platform, "前置调用的请求预处理缺少环境能力或凭据"));
                } else {
                    precondition = true;
                    feedback.add(item(FeedbackType.PRECONDITION_FAILED, intent.intentId(), stepOf(ir, id), id, null, http, platform, "前置调用失败"));
                }
            }
            if (target && prepareFailure != null) {
                inconclusive = true;
                var type = ENVIRONMENT_PREPARE.contains(prepareFailure.responseCode()) ? FeedbackType.UNOBSERVABLE : FeedbackType.MATERIALIZATION_ERROR;
                feedback.add(item(type, intent.intentId(), stepOf(ir, id), id, null, null, platform, "目标调用的请求预处理失败，请求未发出"));
            }
            if (outcomeUnknown)
                feedback.add(item(FeedbackType.SIDE_EFFECT_UNKNOWN, intent.intentId(), stepOf(ir, id), id, null, http, platform == null ? "IN_FLIGHT" : platform, "写调用结果未知，请人工核查残留"));
        }
        String cleanup = report == null ? null : report.cleanupStatus();
        if (cleanup != null && Set.of("FAIL", "UNKNOWN", "NOT_RUN").contains(cleanup)) {
            unknown = true;
            feedback.add(item(FeedbackType.SIDE_EFFECT_UNKNOWN, intent.intentId(), null, null, null, null, "CLEANUP_" + cleanup, "清理未成功完成，可能留有残留资源"));
        }
        Conclusion conclusion = unknown ? Conclusion.SIDE_EFFECT_UNKNOWN
                : contradicted ? Conclusion.CONTRADICTED
                : precondition ? Conclusion.PRECONDITION_FAILED
                : inconclusive || dataInvalid ? Conclusion.INCONCLUSIVE
                : Conclusion.CONSISTENT;
        return new Observation(intent.intentId(), round, "PROBE", outcome.runId(), conclusion, List.copyOf(assertions), List.copyOf(calls), dedupe(feedback),
                List.copyOf(evidence), cleanup);
    }

    // ------------------------------------------------------------------ helpers

    static List<FeedbackItem> missing(TestIntent intent) {
        List<FeedbackItem> result = new ArrayList<>();
        for (var e : intent.expectations()) if (e.sourceType() == SourceType.MISSING)
            result.add(item(FeedbackType.EXPECTATION_MISSING, intent.intentId(), null, e.callId(), e.assertion().id(), null, null,
                    e.note() == null ? "缺少预期依据" : e.note()));
        return result;
    }

    static Set<String> failedIds(JmeterProcessExecutor.Sample sample) {
        if (sample == null || sample.success() || sample.failure() == null) return Set.of();
        String failure = sample.failure();
        int start = failure.indexOf(FAILURE_PREFIX);
        if (start < 0) return Set.of();
        String list = failure.substring(start + FAILURE_PREFIX.length());
        int schema = list.indexOf(" | schema:");
        if (schema >= 0) list = list.substring(0, schema);
        Set<String> ids = new LinkedHashSet<>();
        for (String token : list.split(",")) if (!token.isBlank()) ids.add(token.strip());
        return ids;
    }

    static Map<String, Map<String, Object>> actuals(List<Map<String, Object>> evidence) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (evidence == null) return result;
        for (var line : evidence) {
            if (!(line.get("callId") instanceof String callId) || !(line.get("assertions") instanceof List<?> list)) continue;
            for (var item : list) if (item instanceof Map<?, ?> a && a.get("id") instanceof String id)
                result.computeIfAbsent(callId, k -> new HashMap<>()).put(id, a.get("actual"));
        }
        return result;
    }

    static boolean numeric(String code) { return code != null && code.matches("[1-5][0-9]{2}"); }

    static String stepOf(TestIR ir, String callId) {
        if (ir.executionSteps() == null) return null;
        return ir.executionSteps().stream().filter(g -> g.callIds().contains(callId)).map(CallGroup::id).findFirst().orElse(null);
    }

    private static String check(Assertion a) { return a.kind(); }

    private static String code(String error) {
        if (error == null) return null;
        int colon = error.indexOf(':');
        String head = (colon > 0 ? error.substring(0, colon) : error).strip();
        return head.length() > 60 ? head.substring(0, 60) : head;
    }

    static FeedbackItem item(FeedbackType type, String intentId, String stepId, String callId, String assertionId, String http, String platform, String message) {
        String id = "fb_" + hash(Arrays.asList(type.name(), intentId, callId, assertionId, platform)).substring(0, 20);
        return new FeedbackItem(id, type, type.route(), intentId, stepId, callId, assertionId, http, platform, message);
    }

    static List<FeedbackItem> dedupe(List<FeedbackItem> items) {
        Map<String, FeedbackItem> unique = new LinkedHashMap<>();
        items.forEach(i -> unique.putIfAbsent(i.feedbackId(), i));
        return List.copyOf(unique.values());
    }
}
