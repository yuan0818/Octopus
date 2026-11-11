package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

import io.yuan.octopus.scenario.testing.FlowFailure;
import io.yuan.octopus.scenario.testing.FlowModelClient;
import io.yuan.octopus.scenario.testing.IrValidator;
import io.yuan.octopus.scenario.testing.OrderedFlows;
import java.util.*;

/**
 * Planner 的模型来源（ADR-CG-02）：调用 review-agent 的 /case-plan。
 * 模型只提出目标调用、违规输入和断言草稿；结构按平台负向规则构造，预期来源由平台判定并经 {@link PlanGuard} 校验。
 * 第 2 轮起只接收依据 PLANNER 反馈的数据修订，越界修订整体拒绝（PLAN-005～007）。
 */
public class ModelIntentSource {
    public static final int MAX_INTENTS_PER_CALL = 20;
    private final FlowModelClient models;

    public ModelIntentSource(FlowModelClient models) { this.models = models; }

    // ------------------------------------------------------------------ model protocol

    public record ModelAssertion(String kind, String path, Object expected) {}
    public record SlotSpec(String purpose, Boolean uniqueKey) {}
    public record PlanIntent(String intentId, String parentIntentId, String title, String category, String callId,
                             Map<String, Object> overrides, Map<String, Map<String, Object>> data, Map<String, Map<String, SlotSpec>> slots,
                             Integer rejectionStatus, List<ModelAssertion> assertions, String intentionallyViolatedRule,
                             String rationale, List<String> evidence, String preconditionNotes) {}
    public record PlanOutput(List<PlanIntent> intents, List<Drop> drops, List<Change> changes, List<String> questions) {}

    /** deadline：本次规划可用的总时限（异步任务为任务时限，同步接口为交互时限）；为 null 时只受单次调用上限约束。 */
    public record Context(Flow flow, String flowHash, TestPoint base, Map<String, Operation> operations, Rejection rejection,
                          int limit, String goal, String model, Set<String> coverage, io.yuan.octopus.scenario.testing.ModelDeadline deadline) {
        public Context(Flow flow, String flowHash, TestPoint base, Map<String, Operation> operations, Rejection rejection,
                       int limit, String goal, String model, Set<String> coverage) {
            this(flow, flowHash, base, operations, rejection, limit, goal, model, coverage, null);
        }
        java.time.Duration timeout() { return deadline == null ? FlowModelClient.CASE_CALL : deadline.next(FlowModelClient.CASE_CALL); }
    }

    // ------------------------------------------------------------------ round 1

    public GenerationPlan plan(Context c) {
        var request = context(c, 1, null, List.of());
        int formatCorrections = 0;
        while (true) {
            var response = models.casePlan(c.model(), new LinkedHashMap<>(request), c.timeout());
            try {
                var output = parseJson(response.content(), PlanOutput.class);
                var groups = OrderedFlows.prefix(c.flow(), c.base());
                List<TestIntent> intents = new ArrayList<>();
                List<String> warnings = new ArrayList<>();
                for (var candidate : output.intents() == null ? List.<PlanIntent>of() : output.intents()) {
                    if (intents.size() >= Math.min(c.limit(), MAX_INTENTS_PER_CALL)) break;
                    var built = toIntent(candidate, c.flow(), c.base(), groups, c.operations(), c.rejection());
                    if (built == null) { warnings.add("模型引用了范围外或不存在的调用，已丢弃"); continue; }
                    if (!c.coverage().isEmpty() && !c.coverage().contains(built.category())) continue;
                    intents.add(checked(built, c));
                }
                var questions = output.questions() == null ? List.<String>of() : output.questions().stream().filter(Objects::nonNull).limit(10).map(q -> truncate(q, 400)).toList();
                return new GenerationPlan(1, intents, List.of(), List.of(), questions, warnings, intents.size(), false, response.promptVersion());
            } catch (FlowFailure failure) {
                throw failure;
            } catch (RuntimeException invalid) {
                // 嵌套 null、类型错位等任何结构问题都按格式错误走有界修正（Q-12），不能让整个任务异常终止
                models.recordFormatInvalid("CASE_PLAN", c.model(), response.promptVersion());
                if (formatCorrections++ >= 2) throw new FlowFailure(FlowFailure.Code.MODEL_RESPONSE_INVALID);
                request.put("formatFeedback", Map.of("attempt", formatCorrections, "message", safe(invalid)));
            }
        }
    }

    // ------------------------------------------------------------------ round ≥ 2

    public record Revision(GenerationPlan plan, boolean accepted, List<String> violations) {}

    /**
     * 依据路由给 PLANNER 的反馈修订模型来源的意图。规则来源的意图原样保留（无法自动修复，交给人工）。
     * 越界修订最多语义修正 1 次（Q-12），仍越界则沿用上一轮计划。
     */
    public Revision revise(Context c, GenerationPlan previous, int round, List<FeedbackItem> plannerFeedback, Set<String> frozen) {
        var modelIntents = previous.intents().stream().filter(i -> i.source() == Strategy.MODEL).toList();
        var actionable = plannerFeedback.stream().filter(f -> modelIntents.stream().anyMatch(i -> i.intentId().equals(f.intentId()))).toList();
        if (actionable.isEmpty()) return new Revision(previous, false, List.of("没有可由模型来源处理的反馈"));
        var request = context(c, round, previous, actionable);
        Set<String> feedbackIds = new HashSet<>();
        actionable.forEach(f -> feedbackIds.add(f.feedbackId()));
        int formatCorrections = 0, semanticCorrections = 0;
        List<String> lastViolations = List.of();
        while (true) {
            var response = models.casePlan(c.model(), new LinkedHashMap<>(request), c.timeout());
            GenerationPlan next;
            try {
                var output = parseJson(response.content(), PlanOutput.class);
                next = merge(c, previous, round, output, response.promptVersion());
            } catch (FlowFailure failure) {
                throw failure;
            } catch (RuntimeException invalid) {
                models.recordFormatInvalid("CASE_PLAN", c.model(), response.promptVersion());
                if (formatCorrections++ >= 2) return new Revision(previous, false, List.of("模型修订格式无效：" + safe(invalid)));
                request.put("formatFeedback", Map.of("attempt", formatCorrections, "message", safe(invalid)));
                continue;
            }
            var check = PlanGuard.checkRevision(previous, next, feedbackIds, frozen);
            if (check.ok()) return new Revision(next, true, List.of());
            lastViolations = check.violations();
            if (semanticCorrections++ >= 1) return new Revision(previous, false, lastViolations);
            request.put("revisionFeedback", Map.of("violations", lastViolations,
                    "instruction", "只能修改 data、slots、preconditionNotes，或按反馈拆分／丢弃意图；不得修改预期、目标调用、违规输入或范围；每处修改在 changes 中列出 feedbackIds"));
        }
    }

    private GenerationPlan merge(Context c, GenerationPlan previous, int round, PlanOutput output, String promptVersion) {
        Map<String, TestIntent> before = new LinkedHashMap<>();
        previous.intents().forEach(i -> before.put(i.intentId(), i));
        var groups = OrderedFlows.prefix(c.flow(), c.base());
        Map<String, TestIntent> next = new LinkedHashMap<>(before);
        Set<String> mentioned = new HashSet<>();
        var drops = output.drops() == null ? List.<Drop>of() : output.drops();
        drops.forEach(d -> { if (d != null && d.intentId() != null) next.remove(d.intentId()); });
        for (var p : output.intents() == null ? List.<PlanIntent>of() : output.intents()) {
            if (p == null) continue;
            if (p.intentId() != null && before.containsKey(p.intentId())) {
                var old = before.get(p.intentId());
                if (!mentioned.add(p.intentId())) throw new IllegalArgumentException("意图重复");
                TestIntent updated;
                boolean touchesImmutable = p.callId() != null || p.overrides() != null || p.rejectionStatus() != null || p.assertions() != null
                        || p.intentionallyViolatedRule() != null || p.category() != null;
                if (touchesImmutable) {
                    var rebuilt = toIntent(p, c.flow(), c.base(), groups, c.operations(), c.rejection());
                    if (rebuilt == null) throw new IllegalArgumentException("修订引用了范围外的调用");
                    updated = checked(rebuilt, c).withId(old.intentId());
                    updated = new TestIntent(old.intentId(), old.parentIntentId(), old.source(), updated.sourceRule(), old.title(), updated.category(),
                            updated.targetCallIds(), updated.variation(), updated.endAtStepRef(), updated.suppressedOutputCalls(), updated.intentionallyViolatedRule(),
                            updated.expectations(), updated.dataPlan(), updated.slots(), updated.preconditionNotes(), old.rationale(), updated.issues());
                } else {
                    var data = mergeData(old, p, c);
                    updated = old.withMutable(data.dataPlan(), data.slots(), p.preconditionNotes() == null ? old.preconditionNotes() : truncate(p.preconditionNotes(), 600), data.issues());
                }
                next.put(old.intentId(), updated);
            } else if (p.intentId() == null && p.parentIntentId() != null && before.containsKey(p.parentIntentId())) {
                var parent = before.get(p.parentIntentId());
                var data = mergeData(parent, p, c);
                var split = new TestIntent(null, parent.intentId(), parent.source(), parent.sourceRule(), p.title() == null ? parent.title() + " · 拆分" : truncate(p.title(), 120),
                        parent.category(), parent.targetCallIds(), parent.variation(), parent.endAtStepRef(), parent.suppressedOutputCalls(),
                        parent.intentionallyViolatedRule(), parent.expectations(), data.dataPlan(), data.slots(),
                        p.preconditionNotes() == null ? parent.preconditionNotes() : truncate(p.preconditionNotes(), 600), parent.rationale(), data.issues());
                String id = "it_" + hash(List.of(parent.intentId(), PlanGuard.mutableHash(split))).substring(0, 20);
                next.put(id, split.withId(id));
                // 拆分时允许引用父意图的变更；补一条同反馈的变更记录给新意图
            } else {
                throw new IllegalArgumentException("修订中的意图既不是已有意图，也不是对已有意图的拆分");
            }
        }
        var changes = new ArrayList<>(output.changes() == null ? List.<Change>of() : output.changes());
        return new GenerationPlan(round, List.copyOf(next.values()), drops, changes,
                output.questions() == null ? List.of() : output.questions().stream().filter(Objects::nonNull).limit(10).map(q -> truncate(q, 400)).toList(),
                List.of(), next.size(), false, promptVersion);
    }

    private record DataMerge(Map<String, Map<String, Binding>> dataPlan, Map<String, Map<String, Slot>> slots, List<String> issues) {}

    private DataMerge mergeData(TestIntent old, PlanIntent p, Context c) {
        Map<String, Map<String, Binding>> data = new TreeMap<>();
        old.dataPlan().forEach((k, v) -> data.put(k, new TreeMap<>(v)));
        List<String> issues = new ArrayList<>(old.issues());
        var included = scope(c.flow(), old.endAtStepRef());
        dataLiterals(p.data(), c.flow(), c.operations(), included, old.variation(), data, issues, "数据修订");
        var slots = p.slots() == null ? old.slots() : slotSpecs(p.slots(), c.flow(), c.operations(), included, old.variation(), issues);
        slots.forEach((callId, inputs) -> { var row = data.get(callId); if (row != null) { inputs.keySet().forEach(row::remove); if (row.isEmpty()) data.remove(callId); } });
        return new DataMerge(data, slots, issues);
    }

    // ------------------------------------------------------------------ conversion

    /** 与原 NegativeCaseSuggestionService 负向规则一致；结构性错误返回 null，校验问题记入 issues。 */
    public static TestIntent toIntent(PlanIntent candidate, Flow flow, TestPoint base, List<FlowStep> groups,
                                      Map<String, Operation> operations, Rejection rejection) {
        if (candidate == null || candidate.callId() == null) return null;
        FlowStep group = groups.stream().filter(g -> g.calls().stream().anyMatch(c -> c.id().equals(candidate.callId()))).findFirst().orElse(null);
        if (group == null) return null;
        FlowNode call = group.calls().stream().filter(c -> c.id().equals(candidate.callId())).findFirst().orElseThrow();
        Operation operation = operations.get(call.operationRef());
        List<String> issues = new ArrayList<>();

        Map<String, Binding> focus = new TreeMap<>();
        Map<String, Object> proposed = candidate.overrides() == null ? Map.of() : candidate.overrides();
        if (proposed.isEmpty()) issues.add("没有给出输入修改");
        for (var entry : proposed.entrySet()) {
            if (entry.getKey() == null) { issues.add("输入参数名为空"); continue; }
            Port port = operation.inputs().get(entry.getKey());
            if (port == null) { issues.add("未知输入参数: " + entry.getKey()); continue; }
            if (!IrValidator.matches(entry.getValue(), port.type())) { issues.add("输入类型不符: " + entry.getKey()); continue; }
            if (sensitive(entry.getKey()) || sensitive(port.name())) { issues.add("不能以字面量覆盖敏感参数: " + entry.getKey()); continue; }
            focus.put(entry.getKey(), new Binding("literal", entry.getValue()));
        }

        List<Expectation> expectations = new ArrayList<>();
        Set<String> included = new LinkedHashSet<>();
        for (var step : flow.steps()) { step.calls().forEach(c -> included.add(c.id())); if (step.id().equals(group.id())) break; }
        RuleIntentSource.humanExpectations(base, included).stream().filter(e -> !e.callId().equals(call.id())).forEach(expectations::add);

        Integer status = candidate.rejectionStatus();
        String contract = call.operationRef() + "#response/";
        if (status == null || status < 400 || status > 599) {
            issues.add("拒绝状态码需要 4xx/5xx");
            expectations.add(new Expectation(call.id(), new Assertion("neg_status", "STATUS_EQUALS", null, status), SourceType.MISSING, null, status, "拒绝状态码需要 4xx/5xx"));
        } else {
            expectations.add(new Expectation(call.id(), new Assertion("neg_status", "STATUS_EQUALS", null, status), SourceType.CONTRACT, contract + status, null, null));
        }
        int index = 0;
        for (ModelAssertion a : candidate.assertions() == null ? List.<ModelAssertion>of() : candidate.assertions()) {
            if (a == null || index >= 5) continue;
            String path = normalizePath(a.path());
            try { IrValidator.jsonPath(path); } catch (IllegalArgumentException e) { issues.add("断言路径无效: " + a.path()); continue; }
            if (!"JSON_EQUALS".equals(a.kind()) && !"JSON_EXISTS".equals(a.kind())) { issues.add("不支持的断言类型: " + a.kind()); continue; }
            Object expected = "JSON_EXISTS".equals(a.kind()) ? Boolean.TRUE : a.expected();
            if ("JSON_EQUALS".equals(a.kind()) && expected == null) { issues.add("JSON_EQUALS 需要预期值"); continue; }
            var assertion = new Assertion("neg_" + (++index), a.kind(), path, expected);
            expectations.add(status == null ? new Expectation(call.id(), assertion, SourceType.MISSING, null, expected, "缺少可引用的回应契约")
                    : new Expectation(call.id(), assertion, SourceType.CONTRACT, contract + status + "/" + path, null, null));
        }
        if (index == 0) issues.add("缺少无副作用 / 业务拒绝断言（状态码不足以证明请求被正确拒绝）");

        final String requestedRule = candidate.intentionallyViolatedRule();
        String violated = requestedRule;
        if (requestedRule != null && call.states().stream().noneMatch(s -> (s.modelId() + "/" + s.ruleId()).equals(requestedRule))) {
            issues.add("intentionallyViolatedRule 不是该调用使用的状态规则，已忽略");
            violated = null;
        }
        if ("SERIAL".equals(group.mode()) && !group.calls().getLast().id().equals(call.id())) issues.add("目标调用不是串行步骤末尾，需要先拆分步骤");

        Map<String, Map<String, Binding>> data = new TreeMap<>();
        base.inputOverrides().forEach((k, v) -> { if (included.contains(k)) data.put(k, new TreeMap<>(v)); });
        if (data.containsKey(call.id())) focus.keySet().forEach(data.get(call.id())::remove);
        var variation = new Variation(call.id(), focus, "llm", true);
        dataLiterals(candidate.data(), flow, operations, included, variation, data, issues, "数据");
        // 首轮的数据位同样生效：Operator 依据这些数据位填充取值（OPER-002）
        var slots = candidate.slots() == null ? Map.<String, Map<String, Slot>>of() : slotSpecs(candidate.slots(), flow, operations, included, variation, issues);
        slots.forEach((callId, inputs) -> { var row = data.get(callId); if (row != null) { inputs.keySet().forEach(row::remove); if (row.isEmpty()) data.remove(callId); } });

        String category = candidate.category() == null || candidate.category().isBlank() ? "NEGATIVE"
                : Set.of("NEGATIVE", "AUTH", "STATE", "IDEMPOTENT", "BOUNDARY", "REQUIRED", "ENUM").contains(candidate.category()) ? candidate.category() : "NEGATIVE";
        String title = candidate.title() == null || candidate.title().isBlank() ? call.label() + " · 负向" : truncate(candidate.title(), 120);
        String sourceRule = truncate("llm/" + call.id() + "/" + String.join(",", proposed.keySet()), 200);
        var intent = new TestIntent(null, null, Strategy.MODEL, sourceRule, title, category, List.of(call.id()), variation, group.id(),
                List.of(call.id()), violated, expectations, data, slots,
                candidate.preconditionNotes() == null ? null : truncate(candidate.preconditionNotes(), 600),
                candidate.rationale() == null ? "" : truncate(candidate.rationale(), 600), issues);
        return intent.withId(PlanGuard.intentId(intent));
    }

    /** 预期来源校验（PLAN-002）：不能支撑的预期改记为 MISSING。 */
    static TestIntent checked(TestIntent intent, Context c) {
        var checked = PlanGuard.checkSources(intent, c.flow(), c.operations(), c.rejection());
        var withChecked = intent.withExpectations(checked, intent.issues());
        return withChecked.withId(PlanGuard.intentId(withChecked));
    }

    /**
     * 范围内非被测、非敏感输入的字面值。内层为 null 的映射、未知端口、类型不符或不满足契约约束的取值都记为问题并忽略（OPER-002）。
     */
    static void dataLiterals(Map<String, Map<String, Object>> proposed, Flow flow, Map<String, Operation> operations, Set<String> included,
                             Variation focus, Map<String, Map<String, Binding>> into, List<String> issues, String label) {
        if (proposed == null) return;
        for (var call : proposed.entrySet()) {
            String callId = call.getKey();
            if (callId == null || call.getValue() == null) { issues.add(label + "格式无效：调用 " + callId + " 没有给出输入"); continue; }
            if (!included.contains(callId)) { issues.add(label + "引用了范围外的调用：" + callId); continue; }
            var op = operations.get(node(flow, callId).operationRef());
            for (var entry : call.getValue().entrySet()) {
                String input = entry.getKey();
                if (input == null) { issues.add(label + "格式无效：输入名为空"); continue; }
                if (focus != null && focus.callId().equals(callId) && focus.inputs().containsKey(input)) { issues.add(label + "不能修改被测输入：" + callId + "." + input); continue; }
                var port = op.inputs().get(input);
                if (port == null) { issues.add("未知输入参数: " + input); continue; }
                if (!IrValidator.matches(entry.getValue(), port.type())) { issues.add("输入类型不符: " + callId + "." + input); continue; }
                if (sensitive(input) || sensitive(port.name())) { issues.add("不能以字面量覆盖敏感参数: " + input); continue; }
                var check = SlotConstraints.check(entry.getValue(), SlotConstraints.of(op, input), "VALID");
                if (!check.ok()) { issues.add(label + "取值无效：" + callId + "." + input + " " + check.problem()); continue; }
                if (check.unverified()) issues.add(label + "取值需人工确认：" + callId + "." + input + " " + check.problem());
                into.computeIfAbsent(callId, k -> new TreeMap<>()).put(input, new Binding("literal", entry.getValue()));
            }
        }
    }

    /** Planner 声明的数据位；约束取自契约，不接受模型提供的约束。 */
    static Map<String, Map<String, Slot>> slotSpecs(Map<String, Map<String, SlotSpec>> specs, Flow flow, Map<String, Operation> operations,
                                                    Set<String> included, Variation focus, List<String> issues) {
        Map<String, Map<String, Slot>> result = new TreeMap<>();
        for (var call : specs.entrySet()) {
            String callId = call.getKey();
            if (callId == null || call.getValue() == null) { issues.add("数据位格式无效：调用 " + callId + " 没有给出输入"); continue; }
            if (!included.contains(callId)) { issues.add("数据位引用了范围外的调用：" + callId); continue; }
            var op = operations.get(node(flow, callId).operationRef());
            for (var entry : call.getValue().entrySet()) {
                String input = entry.getKey();
                var spec = entry.getValue();
                var port = input == null ? null : op.inputs().get(input);
                if (port == null || spec == null || sensitive(input) || sensitive(port.name())) { issues.add("数据位无效：" + callId + "." + input); continue; }
                if (focus != null && focus.callId().equals(callId) && focus.inputs().containsKey(input)) { issues.add("数据位不能覆盖被测输入：" + callId + "." + input); continue; }
                String purpose = "VIOLATING".equals(spec.purpose()) ? "VIOLATING" : "VALID";
                result.computeIfAbsent(callId, k -> new TreeMap<>()).put(input, new Slot(port.type(), purpose, SlotConstraints.of(op, input), Boolean.TRUE.equals(spec.uniqueKey())));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ context for the model

    private Map<String, Object> context(Context c, int round, GenerationPlan previous, List<FeedbackItem> feedback) {
        var groups = OrderedFlows.prefix(c.flow(), c.base());
        List<FlowNode> calls = groups.stream().flatMap(g -> g.calls().stream()).toList();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("round", round);
        context.put("goal", c.goal() == null ? "" : c.goal());
        context.put("limit", Math.min(c.limit(), MAX_INTENTS_PER_CALL));
        context.put("coverage", c.coverage().isEmpty() ? List.of("NEGATIVE", "AUTH", "STATE", "IDEMPOTENT") : new ArrayList<>(c.coverage()));
        context.put("basePoint", Map.of("id", c.base().id(), "title", c.base().title(), "covers", c.base().covers()));
        context.put("calls", calls.stream().map(call -> callView(call, c.operations().get(call.operationRef()))).toList());
        context.put("stateModels", c.flow().stateModels());
        context.put("existingCases", c.flow().testPoints().stream().filter(p -> p.endAtStepRef() != null)
                .map(p -> Map.of("title", p.title(), "inputOverrides", p.inputOverrides())).limit(30).toList());
        if (previous != null) {
            context.put("previousPlan", previous.intents().stream().filter(i -> i.source() == Strategy.MODEL).map(i -> {
                var view = new LinkedHashMap<String, Object>();
                view.put("intentId", i.intentId());
                view.put("title", i.title());
                view.put("category", i.category());
                view.put("callId", i.variation() == null ? null : i.variation().callId());
                view.put("data", i.dataPlan());
                view.put("slots", i.slots());
                view.put("preconditionNotes", i.preconditionNotes());
                return view;
            }).toList());
            // ADR-CG-06：反馈只含分类、定位和错误码，不含被测系统的实际值
            context.put("feedback", feedback.stream().map(FeedbackView::of).toList());
        }
        return context;
    }

    static Map<String, Object> callView(FlowNode call, Operation operation) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", call.id());
        view.put("label", call.label());
        view.put("method", operation.method());
        view.put("path", operation.path());
        Map<String, Object> inputs = new TreeMap<>();
        Object params = operation.requestSchema() == null ? null : operation.requestSchema().get("x-octopus-parameters");
        operation.inputs().forEach((name, port) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("location", port.location());
            p.put("type", port.type());
            p.put("required", port.required());
            p.put("sensitive", sensitive(name) || sensitive(port.name()));
            if (params instanceof Map<?, ?> m && m.get(name) instanceof Map<?, ?> constraint) p.put("constraints", constraint);
            inputs.put(name, p);
        });
        view.put("inputs", inputs);
        Map<String, Object> responses = new TreeMap<>();
        if (operation.responseSchema() != null) operation.responseSchema().forEach((status, schema) -> {
            Map<String, Object> fields = new TreeMap<>();
            if (schema instanceof Map<?, ?> m && m.get("properties") instanceof Map<?, ?> props) props.forEach((k, v) -> {
                var f = new LinkedHashMap<String, Object>();
                if (v instanceof Map<?, ?> field) {
                    if (field.get("type") != null) f.put("type", field.get("type"));
                    if (field.get("enum") instanceof List<?> values) f.put("enum", values.stream().limit(50).toList());
                    if (field.containsKey("const")) f.put("const", field.get("const"));
                }
                fields.put(String.valueOf(k), f);
            });
            responses.put(status, fields);
        });
        view.put("responses", responses);
        view.put("stateRules", call.states().stream().map(s -> s.modelId() + "/" + s.ruleId()).toList());
        return view;
    }

    // ------------------------------------------------------------------ helpers

    public static <T> T parseJson(String content, Class<T> type) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("空响应");
        String json = content.strip();
        if (json.startsWith("```")) {
            int first = json.indexOf('\n'), last = json.lastIndexOf("```");
            if (!(first > 0 && last > first)) throw new IllegalArgumentException("无法解析围栏");
            json = json.substring(first + 1, last).strip();
        }
        if (json.length() > 200_000) throw new IllegalArgumentException("输出过大");
        try { return read(json, type); }
        catch (com.google.gson.JsonParseException | IllegalStateException e) { throw new IllegalArgumentException("JSON 语法错误"); }
    }

    static String normalizePath(String path) {
        if (path == null) return "";
        if (path.equals("$.body")) return "$";
        if (path.startsWith("$.body.") || path.startsWith("$.body[")) return "$" + path.substring("$.body".length());
        return path;
    }

    static FlowNode node(Flow flow, String id) {
        return flow.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("未知调用"));
    }

    static Set<String> scope(Flow flow, String endStep) {
        if (endStep == null) { var all = new LinkedHashSet<String>(); flow.nodes().forEach(n -> all.add(n.id())); return all; }
        return RuleIntentSource.included(flow, endStep);
    }

    public static boolean sensitive(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*(password|passwd|secret|token|credential|apikey|api_key|authorization|cookie|session).*");
    }

    static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String safe(RuntimeException e) {
        String m = e instanceof IllegalArgumentException ? e.getMessage() : null;
        return m == null || m.length() > 200 ? "输出结构不符合约定（例如出现了 null 或类型错位）" : m;
    }
}
