package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.scenario.testing.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * 三角色闭环的编排（设计 5.1、8.5）。每次 {@link #advance} 只做一个可检查点的工作单元，调用方在每个单元后持久化快照，
 * 因此从检查点恢复不会重复已完成的工作；探测先记录 runId 再提交，提交本身幂等，绝不重放（LOOP-005）。
 * <p>角色写权限（ROLE-001）：Planner 只产出计划，Operator 只产出候选与探测，Observer 只产出观察；快照只在这里写入。
 */
public class CaseGenerationEngine {

    // ------------------------------------------------------------------ snapshot

    public static final class Task {
        public int formatVersion = 1;
        public String id, requestHash, createdAt, createdBy, updatedAt;
        public TaskRequest request;
        public TaskState state = TaskState.QUEUED;
        public Phase phase;
        public int round;
        public String owner;
        public long leaseUntil;
        public long revision;
        public boolean cancelRequested;
        public String flowHash;
        public String endReason;
        public String errorCode, error;
        public long startedMillis, deadlineMillis;
        public int probesUsed;
        public Map<String, Integer> probesPerIntent = new TreeMap<>();
        public Set<String> observedDigests = new TreeSet<>();
        public List<RoundRecord> rounds = new ArrayList<>();
        public List<RoleCall> roleCalls = new ArrayList<>();
        public List<Event> events = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();

        public RoundRecord current() { return rounds.isEmpty() ? null : rounds.getLast(); }
        public RoundRecord previous() { return rounds.size() < 2 ? null : rounds.get(rounds.size() - 2); }
        void event(String kind, String message) {
            revision++;
            updatedAt = Instant.now().toString();
            events.add(new Event(revision, updatedAt, round, kind, message));
            while (events.size() > 300) events.removeFirst();
        }
    }

    public static final class RoundRecord {
        public int round;
        public GenerationPlan plan;
        public Map<String, CandidateDraft> candidates = new LinkedHashMap<>();
        public Map<String, String> probes = new LinkedHashMap<>();        // intentId → runId（先记录后提交）
        public Map<String, Observation> observations = new LinkedHashMap<>();
        public Map<String, String> skipped = new LinkedHashMap<>();       // intentId → 本轮不探测的原因
        public Set<String> carried = new TreeSet<>();                      // 沿用上一轮结果的意图
        public List<String> planViolations = new ArrayList<>();
        public String endReason;
    }

    public record RoleCall(String role, int round, String intentId, String inputDigest, String model, String promptVersion, String outcome, String at) {}
    public record Event(long revision, String at, int round, String kind, String message) {}

    // ------------------------------------------------------------------ collaborators

    public interface ProbeGateway {
        ProbeRun submit(ProbeExecutionService.Submission submission);
        Optional<ProbeRun> get(String id);
        void cancel(String id);
        ProbeEnvironmentService.Active environment(String name);
    }

    public record Budget(int maxRounds, int maxProbes, int maxProbesPerIntent, long taskTimeoutSeconds, long probeTimeoutSeconds) {
        public static final Budget DEFAULT = new Budget(3, 30, 2, 1800, 300);
        public Budget {
            // G-Q1 硬上限：配置只能收紧，不能放宽
            maxRounds = Math.max(1, Math.min(3, maxRounds));
            maxProbes = Math.max(0, Math.min(30, maxProbes));
            maxProbesPerIntent = Math.max(1, Math.min(2, maxProbesPerIntent));
            taskTimeoutSeconds = Math.max(60, Math.min(1800, taskTimeoutSeconds));
            probeTimeoutSeconds = Math.max(10, Math.min(300, probeTimeoutSeconds));
        }
    }

    private final BusinessKnowledgeStore store;
    private final ModelIntentSource modelSource;
    private final DataSlotFiller slots;
    private final StaticChecks checks;
    private final ProbeGateway probes;
    private final Budget budget;
    private final java.time.Clock clock;
    long pollMillis = 1000;

    public CaseGenerationEngine(BusinessKnowledgeStore store, ModelIntentSource modelSource, DataSlotFiller slots, StaticChecks checks,
                                ProbeGateway probes, Budget budget, java.time.Clock clock) {
        this.store = store;
        this.modelSource = modelSource;
        this.slots = slots;
        this.checks = checks;
        this.probes = probes;
        this.budget = budget;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ request validation

    public static void validate(TaskRequest r) {
        require(r != null, "请求不能为空");
        require(r.flowId() != null && !r.flowId().isBlank() && r.testPointId() != null && !r.testPointId().isBlank(), "请选择已确认流程版本与基础测试点");
        require(r.limit() >= 1 && r.limit() <= 100, "每批最多生成 100 个候选");
        require(r.strategies() != null && !r.strategies().isEmpty(), "请至少选择一种生成策略");
        require(r.goal() == null || r.goal().length() <= 4000, "补充说明最多 4000 字");
        if (r.rejection() != null && r.rejection().status() != null)
            require(r.rejection().status() >= 400 && r.rejection().status() <= 599 && r.rejection().evidence() != null && !r.rejection().evidence().isBlank(),
                    "负向预期需要明确拒绝状态码及业务依据");
        require(r.probe() != null && r.probe().mode() != null, "请明确选择是否开启探测（LOOP-008：默认关闭）");
        if (r.probe().mode() == ProbeMode.ON) {
            require(r.probe().environment() != null, "开启探测时必须选择环境");
            ProjectContextService.environment(r.probe().environment());
        }
    }

    // ------------------------------------------------------------------ advance

    /** 执行一个工作单元。返回 false 表示任务已到终态。 */
    public boolean advance(Task t, BooleanSupplier cancelRequested) {
        if (terminal(t.state)) return false;
        if (t.cancelRequested || cancelRequested.getAsBoolean()) { cancel(t); return false; }
        if (t.startedMillis == 0) { t.startedMillis = clock.millis(); t.deadlineMillis = t.startedMillis + budget.taskTimeoutSeconds() * 1000; }
        if (clock.millis() > t.deadlineMillis) {
            fail(t, "TASK_TIMEOUT", "任务超过总时限（" + budget.taskTimeoutSeconds() + " 秒），已完成的轮次保留");
            return false;
        }
        var ctx = context(t);
        if (t.round == 0) { startRound(t, 1); return true; }
        var round = t.current();
        switch (t.phase) {
            case PLANNING -> plan(t, ctx, round);
            case MATERIALIZING -> materializeNext(t, ctx, round);
            case PROBING -> probeNext(t, ctx, round, cancelRequested);
            case OBSERVING -> observe(t, ctx, round);
        }
        return !terminal(t.state);
    }

    /** 同步接口把任务失败转换为调用方可理解的异常；任务超时对调用方而言就是模型超时。 */
    public static RuntimeException failure(Task t) {
        if ("TASK_TIMEOUT".equals(t.errorCode)) return new FlowFailure(FlowFailure.Code.MODEL_TIMEOUT, null, "本次生成超过 " + ((t.deadlineMillis - t.startedMillis) / 1000) + " 秒时限");
        if (t.errorCode != null) for (var code : FlowFailure.Code.values()) if (code.name().equals(t.errorCode)) return new FlowFailure(code);
        return new IllegalArgumentException(t.error == null ? "生成任务失败" : t.error);
    }

    public static boolean terminal(TaskState s) {
        return s == TaskState.READY || s == TaskState.NEEDS_INPUT || s == TaskState.FAILED || s == TaskState.CANCELLED;
    }

    record Ctx(FlowRevision revision, Flow flow, TestPoint base, Map<String, Operation> operations, Set<String> coverage) {}

    Ctx context(Task t) {
        var revision = store.flow(t.request.flowId(), t.request.flowRevision());
        require("CONFIRMED".equals(revision.status()), "请先确认作为生成依据的流程版本");
        if (t.flowHash == null) t.flowHash = revision.hash();
        // PLAN-001：任务绑定确认时的流程版本，版本内容变化后停止
        require(t.flowHash.equals(revision.hash()), "作为生成依据的流程版本已改变，请重新创建任务");
        Flow flow = revision.definition();
        require(flow.steps() != null, "请先将历史流程迁移为单向流程");
        var base = flow.testPoints().stream().filter(p -> p.id().equals(t.request.testPointId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown test point"));
        Map<String, Operation> operations = new LinkedHashMap<>();
        for (var call : flow.nodes()) operations.putIfAbsent(call.operationRef(), store.operation(call.operationRef()));
        if (flow.cleanup() != null) for (var c : flow.cleanup()) operations.putIfAbsent(c.call().operationRef(), store.operation(c.call().operationRef()));
        Set<String> coverage = new TreeSet<>(t.request.coverage() == null ? List.of() : t.request.coverage());
        return new Ctx(revision, flow, base, operations, coverage);
    }

    private void startRound(Task t, int number) {
        var record = new RoundRecord();
        record.round = number;
        t.rounds.add(record);
        t.round = number;
        t.phase = Phase.PLANNING;
        t.state = TaskState.RUNNING;
        t.event("ROUND", "第 " + number + " 轮开始规划");
    }

    // ------------------------------------------------------------------ Planner

    private void plan(Task t, Ctx c, RoundRecord round) {
        var mc = new ModelIntentSource.Context(c.flow(), c.revision().hash(), c.base(), c.operations(), t.request.rejection(),
                t.request.limit(), t.request.goal(), t.request.model(), c.coverage(), ModelDeadline.at(t.deadlineMillis, clock));
        if (round.round == 1) {
            List<TestIntent> intents = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            List<String> questions = new ArrayList<>();
            String promptVersion = null;
            if (t.request.strategies().contains(Strategy.RULE)) {
                var rule = RuleIntentSource.plan(c.flow(), c.base(), c.operations(), t.request.rejection(), c.coverage());
                intents.addAll(rule.intents());
                warnings.addAll(rule.warnings());
                t.roleCalls.add(new RoleCall("PLANNER", 1, null, digest(t, "RULE"), null, "rule@1", "OK", now()));
            }
            if (t.request.strategies().contains(Strategy.MODEL)) {
                require(modelSource != null, "模型策略不可用");
                try {
                    var model = modelSource.plan(mc);
                    intents.addAll(model.intents());
                    warnings.addAll(model.warnings());
                    questions.addAll(model.questions());
                    promptVersion = model.promptVersion();
                    t.roleCalls.add(new RoleCall("PLANNER", 1, null, digest(t, "MODEL"), t.request.model(), promptVersion, "OK", now()));
                } catch (FlowFailure failure) {
                    t.roleCalls.add(new RoleCall("PLANNER", 1, null, digest(t, "MODEL"), t.request.model(), null, failure.code.name(), now()));
                    throw failure;
                }
            }
            // 去重（CASE-013、PLAN-008）：同一物化身份只保留一个；非基线意图与已确认测试点重复时跳过
            Set<String> existing = new HashSet<>();
            for (var p : c.flow().testPoints()) { existing.add(identity(p, false)); existing.add(identity(p, true)); }
            Map<String, TestIntent> unique = new LinkedHashMap<>();
            for (var intent : intents) {
                var m = IntentMaterializer.materialize(intent, c.revision().hash(), c.base().id(), t.id, 1, t.request.model(), Map.of(), Set.of());
                String key = m.fingerprint();
                if (!intent.baseline() && existing.contains(identity(m.point(), intent.source() == Strategy.MODEL))) continue;
                if (unique.containsKey(key) || unique.values().stream().anyMatch(i -> i.intentId().equals(intent.intentId()))) continue;
                unique.put(key, intent);
            }
            int total = unique.size();
            var selected = unique.values().stream().limit(t.request.limit()).toList();
            if (total > t.request.limit()) warnings.add("本次规则范围内共 " + total + " 个候选，受上限限制只保留 " + t.request.limit() + " 个");
            round.plan = new GenerationPlan(1, selected, List.of(), List.of(), questions, warnings, total, total > t.request.limit(), promptVersion);
            t.warnings = List.copyOf(new LinkedHashSet<>(warnings));
            t.phase = Phase.MATERIALIZING;
            t.event("PLANNED", "Planner 产出 " + selected.size() + " 个测试意图");
            return;
        }
        var previous = t.previous();
        var frozen = frozen(previous);
        var feedback = plannerFeedback(previous, frozen);
        var revision = modelSource.revise(mc, previous.plan, round.round, feedback, frozen);
        t.roleCalls.add(new RoleCall("PLANNER", round.round, null, digest(t, feedback.stream().map(FeedbackItem::feedbackId).sorted().toList()),
                t.request.model(), revision.plan().promptVersion(), revision.accepted() ? "OK" : "REJECTED", now()));
        if (!revision.accepted()) {
            // 越界或无效修订：沿用上一轮计划并结束闭环（设计 5.3）
            t.rounds.removeLast();
            t.round = previous.round;
            previous.planViolations.addAll(revision.violations());
            finish(t, previous, revision.violations().isEmpty() ? "NO_PROGRESS" : "PLAN_REJECTED");
            return;
        }
        if (PlanGuard.planMutableHash(revision.plan()).equals(PlanGuard.planMutableHash(previous.plan))) {
            t.rounds.removeLast();
            t.round = previous.round;
            finish(t, previous, "NO_PROGRESS");
            return;
        }
        round.plan = revision.plan();
        // 未修订的意图沿用上一轮的候选与观察，不重复物化和探测
        Map<String, TestIntent> before = new HashMap<>();
        previous.plan.intents().forEach(i -> before.put(i.intentId(), i));
        for (var intent : round.plan.intents()) {
            var old = before.get(intent.intentId());
            if (old != null && PlanGuard.mutableHash(old).equals(PlanGuard.mutableHash(intent)) && previous.candidates.containsKey(intent.intentId())) {
                round.candidates.put(intent.intentId(), previous.candidates.get(intent.intentId()));
                if (previous.observations.containsKey(intent.intentId())) round.observations.put(intent.intentId(), previous.observations.get(intent.intentId()));
                if (previous.probes.containsKey(intent.intentId())) round.probes.put(intent.intentId(), previous.probes.get(intent.intentId()));
                round.carried.add(intent.intentId());
            }
        }
        t.phase = Phase.MATERIALIZING;
        t.event("PLANNED", "Planner 依据 " + feedback.size() + " 条反馈修订计划");
    }

    static String identity(TestPoint p, boolean withRule) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("overrides", p.inputOverrides());
        identity.put("assertions", p.assertions());
        identity.put("end", p.endAtStepRef());
        identity.put("suppressed", p.suppressedOutputCalls());
        if (withRule) identity.put("rule", p.intentionallyViolatedRule());
        return hash(identity);
    }

    // ------------------------------------------------------------------ Operator: materialize

    private void materializeNext(Task t, Ctx c, RoundRecord round) {
        var next = round.plan.intents().stream().filter(i -> !round.candidates.containsKey(i.intentId())).findFirst();
        if (next.isEmpty()) {
            t.phase = t.request.probe().mode() == ProbeMode.ON ? Phase.PROBING : Phase.OBSERVING;
            t.event("MATERIALIZED", "Operator 完成物化与静态校验");
            return;
        }
        var intent = next.get();
        Map<String, Map<String, Binding>> fills = Map.of();
        List<String> issues = new ArrayList<>();
        if (!intent.slots().isEmpty()) {
            var fill = slots.fill(intent, c.flow(), c.operations(), t.request.model(), ModelDeadline.at(t.deadlineMillis, clock));
            fills = fill.bindings();
            issues.addAll(fill.issues());
            if (fill.modelCalls() > 0)
                t.roleCalls.add(new RoleCall("OPERATOR", round.round, intent.intentId(), hash(intent.slots()), t.request.model(), fill.promptVersion(), "OK", now()));
        }
        var m = IntentMaterializer.materialize(intent, c.revision().hash(), c.base().id(), t.id, round.round, t.request.model(), fills, t.observedDigests);
        issues.addAll(m.issues());
        var checked = checks.check(c.revision(), m.point(), issues, t.request.probe());
        var env = t.request.probe().mode() == ProbeMode.ON ? probes.environment(t.request.probe().environment()) : null;
        String reason = StaticChecks.ineligibility(checked.result(), checked.ir(), c.flow(), env, t.request.probe());
        List<String> draftIssues = issues.stream().filter(i -> !i.startsWith("数据位")).toList();
        round.candidates.put(intent.intentId(), new CandidateDraft(intent.intentId(), round.round, m.point(), m.fingerprint(), checked.result(),
                reason == null, reason, draftIssues));
    }

    // ------------------------------------------------------------------ Operator: probe

    private void probeNext(Task t, Ctx c, RoundRecord round, BooleanSupplier cancelRequested) {
        // 已记录 runId 但尚无观察：提交（幂等）并等待终态
        var pending = round.probes.entrySet().stream().filter(e -> !round.observations.containsKey(e.getKey())).findFirst();
        if (pending.isPresent()) {
            String intentId = pending.get().getKey(), runId = pending.get().getValue();
            var intent = intent(round, intentId);
            var draft = round.candidates.get(intentId);
            var row = probes.get(runId).orElse(null);
            if (row == null) {
                // 检查点已记录 runId 但尚未插入：此前没有发出任何请求，可以安全提交
                var checked = checks.check(c.revision(), draft.point(), List.of(), t.request.probe());
                var env = probes.environment(t.request.probe().environment());
                if (checked.ir() == null || checked.profile() == null || env == null) {
                    round.skipped.put(intentId, env == null ? "环境探测授权已撤销" : "执行配置不可用");
                    round.probes.remove(intentId);
                    if (env == null) t.event("PROBE_DISABLED", "环境探测授权已撤销，后续转为纯静态模式");
                    return;
                }
                long remaining = Math.max(10, (t.deadlineMillis - clock.millis()) / 1000 - 30);
                row = probes.submit(new ProbeExecutionService.Submission(runId, t.id, round.round, intentId, hash(draft.point()), checked.ir(),
                        checked.profile(), env, Math.min(budget.probeTimeoutSeconds(), remaining)));
                t.event("PROBE_SUBMITTED", "Operator 提交探测 " + intentId);
            }
            boolean cancelSent = false;
            long waitDeadline = clock.millis() + (budget.probeTimeoutSeconds() + 120) * 1000;
            while (!row.terminal()) {
                if (!cancelSent && (t.cancelRequested || cancelRequested.getAsBoolean())) { probes.cancel(runId); cancelSent = true; }
                if (clock.millis() > waitDeadline) break;
                sleep();
                row = probes.get(runId).orElse(row);
            }
            if (!row.terminal()) { probes.cancel(runId); return; }   // 下一次 advance 继续等待，不重新提交
            var checked = checks.check(c.revision(), draft.point(), List.of(), new ProbeConfig(ProbeMode.OFF, null, null, null, null));
            var outcome = outcome(row);
            var observation = ObservationClassifier.observeProbe(intent, draft, checked.ir() == null ? irFallback(c, draft) : checked.ir(), outcome, round.round);
            round.observations.put(intentId, observation);
            collectDigests(t, row);
            t.roleCalls.add(new RoleCall("OBSERVER", round.round, intentId, hash(List.of(runId, String.valueOf(row.state))), null, "classifier@1", observation.conclusion().name(), now()));
            t.event("OBSERVED", "Observer：" + intentId + " → " + observation.conclusion());
            if (t.cancelRequested || cancelRequested.getAsBoolean()) cancel(t);
            return;
        }
        // 选择下一个可探测的意图（LOOP-003、LOOP-004、OPER-006、OPER-009）
        var env = probes.environment(t.request.probe().environment());
        if (env == null) {
            if (!round.skipped.containsValue("环境探测授权已撤销")) t.event("PROBE_DISABLED", "环境未开启生成探测或授权已撤销，转为纯静态模式");
            for (var intent : round.plan.intents()) if (!round.observations.containsKey(intent.intentId())) round.skipped.putIfAbsent(intent.intentId(), "环境探测授权已撤销");
            t.phase = Phase.OBSERVING;
            return;
        }
        Set<String> unknown = unknownIntents(t);
        var skipSignatures = failedPreconditions(c, round);
        for (var intent : prioritized(round.plan.intents())) {
            String id = intent.intentId();
            if (round.observations.containsKey(id) || round.probes.containsKey(id) || round.skipped.containsKey(id)) continue;
            var draft = round.candidates.get(id);
            String reason = null;
            if (!draft.probeEligible()) reason = draft.probeIneligibleReason();
            else if (unknown.contains(id)) reason = "该意图曾出现结果未知的写调用，需人工核查后才能再次探测";
            else if (t.probesUsed >= budget.maxProbes()) reason = TASK_LIMIT;
            else if (t.probesPerIntent.getOrDefault(id, 0) >= budget.maxProbesPerIntent()) reason = PER_INTENT_LIMIT;
            else if (t.deadlineMillis - clock.millis() < 60_000) reason = TIME_LIMIT;
            else if (skipped(c, intent, draft, skipSignatures)) reason = SHARED_PRECONDITION;
            if (reason != null) { round.skipped.put(id, reason); continue; }
            String runId = UUID.randomUUID().toString();
            round.probes.put(id, runId);
            t.probesUsed++;
            t.probesPerIntent.merge(id, 1, Integer::sum);
            return;   // 先持久化 runId，再提交
        }
        t.phase = Phase.OBSERVING;
    }

    private static List<TestIntent> prioritized(List<TestIntent> intents) {
        List<TestIntent> ordered = new ArrayList<>();
        intents.stream().filter(TestIntent::baseline).forEach(ordered::add);
        intents.stream().filter(i -> !i.baseline()).forEach(ordered::add);
        return ordered;
    }

    /** LOOP-004：基线的前置失败调用及其有效绑定。 */
    private Map<String, String> failedPreconditions(Ctx c, RoundRecord round) {
        Map<String, String> signatures = new HashMap<>();
        for (var intent : round.plan.intents()) {
            if (!intent.baseline()) continue;
            var obs = round.observations.get(intent.intentId());
            if (obs == null || obs.conclusion() != Conclusion.PRECONDITION_FAILED) continue;
            var draft = round.candidates.get(intent.intentId());
            for (var f : obs.feedback()) if (f.type() == FeedbackType.PRECONDITION_FAILED && f.callId() != null)
                signatures.put(f.callId(), bindingSignature(c.flow(), draft.point(), f.callId()));
        }
        return signatures;
    }

    private boolean skipped(Ctx c, TestIntent intent, CandidateDraft draft, Map<String, String> signatures) {
        if (signatures.isEmpty() || intent.baseline()) return false;
        var scope = ModelIntentSource.scope(c.flow(), intent.endAtStepRef());
        for (var entry : signatures.entrySet()) {
            String call = entry.getKey();
            if (!scope.contains(call) || intent.targetCallIds().contains(call)) continue;
            if (entry.getValue().equals(bindingSignature(c.flow(), draft.point(), call))) return true;
        }
        return false;
    }

    static String bindingSignature(Flow flow, TestPoint point, String callId) {
        var node = flow.nodes().stream().filter(n -> n.id().equals(callId)).findFirst().orElseThrow();
        Map<String, Binding> inputs = new TreeMap<>(node.inputs());
        inputs.putAll(point.inputOverrides().getOrDefault(callId, Map.of()));
        return hash(inputs);
    }

    private ObservationClassifier.ProbeOutcome outcome(ProbeRun row) {
        JmeterProcessExecutor.Result result = null;
        if (row.reportJson != null) {
            var report = GSON.fromJson(row.reportJson, com.google.gson.JsonObject.class);
            if (report.has("result")) result = GSON.fromJson(report.get("result"), JmeterProcessExecutor.Result.class);
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (row.evidenceJson != null) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> parsed = GSON.fromJson(row.evidenceJson, List.class);
            if (parsed != null) evidence.addAll(parsed);
        }
        String state = row.state;
        if ("INTERRUPTED".equals(state) || "FAILED".equals(state) || "REJECTED".equals(state)) {
            if (!row.requestsMayHaveBeenSent) return new ObservationClassifier.ProbeOutcome(row.id, state, false, result, evidence);
        }
        return new ObservationClassifier.ProbeOutcome(row.id, state, row.requestsMayHaveBeenSent, result, evidence);
    }

    private TestIR irFallback(Ctx c, CandidateDraft draft) {
        // 静态校验在提交前已通过；规则或流程未变时总能重建 IR
        return checks.check(c.revision(), draft.point(), List.of(), new ProbeConfig(ProbeMode.OFF, null, null, null, null)).ir();
    }

    private void collectDigests(Task t, ProbeRun row) {
        if (row.evidenceJson == null) return;
        @SuppressWarnings("unchecked") List<Map<String, Object>> lines = GSON.fromJson(row.evidenceJson, List.class);
        if (lines == null) return;
        for (var line : lines) if (line.get("outputs") instanceof List<?> outs) for (var o : outs)
            if (o instanceof Map<?, ?> m && m.get("sha256") instanceof String sha) t.observedDigests.add(sha);
    }

    private Set<String> unknownIntents(Task t) {
        Set<String> result = new HashSet<>();
        for (var r : t.rounds) r.observations.forEach((id, o) -> { if (o.conclusion() == Conclusion.SIDE_EFFECT_UNKNOWN) result.add(id); });
        return result;
    }

    // ------------------------------------------------------------------ Observer + loop control

    static final String SHARED_PRECONDITION = "正向基线的公共前置失败，本轮跳过";

    private void observe(Task t, Ctx c, RoundRecord round) {
        var inherited = failedPreconditionFeedback(round);
        for (var intent : round.plan.intents()) {
            if (round.observations.containsKey(intent.intentId())) continue;
            var draft = round.candidates.get(intent.intentId());
            var observation = ObservationClassifier.observeStatic(intent, draft, round.round);
            if (SHARED_PRECONDITION.equals(round.skipped.get(intent.intentId())) && !inherited.isEmpty()) {
                // LOOP-004：被跳过的意图继承基线的前置失败，Planner 才能为它们修订数据
                List<FeedbackItem> feedback = new ArrayList<>(observation.feedback());
                for (var f : inherited) feedback.add(ObservationClassifier.item(FeedbackType.PRECONDITION_FAILED, intent.intentId(), f.stepId(), f.callId(),
                        null, f.httpStatus(), f.platformCode(), "继承自正向基线的前置失败"));
                observation = new Observation(observation.intentId(), observation.round(), observation.source(), null, Conclusion.NOT_PROBED,
                        observation.assertions(), observation.calls(), ObservationClassifier.dedupe(feedback), observation.evidence(), null);
            }
            round.observations.put(intent.intentId(), observation);
        }
        t.roleCalls.add(new RoleCall("OBSERVER", round.round, null, hash(round.observations.keySet()), null, "classifier@1", "OK", now()));
        var frozen = frozen(round);
        var feedback = plannerFeedback(round, frozen);
        boolean replannable = modelSource != null && t.request.strategies().contains(Strategy.MODEL)
                && feedback.stream().anyMatch(f -> intent(round, f.intentId()).source() == Strategy.MODEL);
        var previous = t.previous();
        if (feedback.isEmpty() || !replannable) {
            boolean exhausted = round.skipped.values().stream().anyMatch(CaseGenerationEngine::budgetReason);
            finish(t, round, exhausted ? "BUDGET" : "CONVERGED");
            return;
        }
        if (previous != null) {
            var before = plannerFeedback(previous, frozen(previous)).stream().map(FeedbackItem::feedbackId).collect(java.util.stream.Collectors.toSet());
            var now = feedback.stream().map(FeedbackItem::feedbackId).collect(java.util.stream.Collectors.toSet());
            if (before.equals(now)) { finish(t, round, "NO_PROGRESS"); return; }
        }
        boolean probing = t.request.probe().mode() == ProbeMode.ON;
        if (round.round >= budget.maxRounds() || probing && t.probesUsed >= budget.maxProbes() || t.deadlineMillis - clock.millis() < 60_000) {
            finish(t, round, "BUDGET");
            return;
        }
        startRound(t, round.round + 1);
    }

    private static List<FeedbackItem> failedPreconditionFeedback(RoundRecord round) {
        List<FeedbackItem> result = new ArrayList<>();
        for (var intent : round.plan.intents()) {
            if (!intent.baseline()) continue;
            var o = round.observations.get(intent.intentId());
            if (o != null && o.conclusion() == Conclusion.PRECONDITION_FAILED)
                o.feedback().stream().filter(f -> f.type() == FeedbackType.PRECONDITION_FAILED).forEach(result::add);
        }
        return result;
    }

    static final String PER_INTENT_LIMIT = "该意图的探测次数已达上限", TASK_LIMIT = "任务探测预算已用尽", TIME_LIMIT = "任务剩余时间不足";
    static boolean budgetReason(String reason) { return PER_INTENT_LIMIT.equals(reason) || TASK_LIMIT.equals(reason) || TIME_LIMIT.equals(reason); }

    static Set<String> frozen(RoundRecord round) {
        Set<String> result = new HashSet<>();
        round.observations.forEach((id, o) -> { if (o.conclusion() == Conclusion.CONTRADICTED || o.conclusion() == Conclusion.SIDE_EFFECT_UNKNOWN) result.add(id); });
        return result;
    }

    static List<FeedbackItem> plannerFeedback(RoundRecord round, Set<String> frozen) {
        List<FeedbackItem> result = new ArrayList<>();
        round.observations.forEach((id, o) -> {
            if (frozen.contains(id)) return;
            o.feedback().stream().filter(f -> f.route() == Route.PLANNER).forEach(result::add);
        });
        return result;
    }

    private void finish(Task t, RoundRecord round, String reason) {
        round.endReason = reason;
        t.endReason = reason;
        t.phase = null;
        boolean blocked = false;
        for (var intent : round.plan.intents()) {
            var draft = round.candidates.get(intent.intentId());
            if (draft == null) continue;
            if (CandidateStatusRule.BLOCKED.equals(CandidateStatusRule.of(intent, draft, round.observations.get(intent.intentId())).status())) blocked = true;
        }
        t.state = blocked || !round.plan.questions().isEmpty() ? TaskState.NEEDS_INPUT : TaskState.READY;
        t.owner = null;
        t.leaseUntil = 0;
        t.event(t.state.name(), "闭环结束（" + reason + "）");
    }

    private void cancel(Task t) {
        var round = t.current();
        if (round != null) round.probes.forEach((intentId, runId) -> { if (!round.observations.containsKey(intentId)) probes.cancel(runId); });
        t.state = TaskState.CANCELLED;
        t.phase = null;
        t.owner = null;
        t.leaseUntil = 0;
        t.endReason = "CANCELLED";
        t.event("CANCELLED", "任务已取消，已完成的轮次保留");
    }

    public static void fail(Task t, String code, String message) {
        t.state = TaskState.FAILED;
        t.errorCode = code;
        t.error = message;
        t.owner = null;
        t.leaseUntil = 0;
        t.event("FAILED", message);
    }

    private static TestIntent intent(RoundRecord round, String id) {
        return round.plan.intents().stream().filter(i -> i.intentId().equals(id)).findFirst().orElseThrow();
    }

    private static String digest(Task t, Object part) {
        return hash(List.of(t.flowHash == null ? "" : t.flowHash, t.request.testPointId(), String.valueOf(t.round), part));
    }

    private void sleep() {
        try { Thread.sleep(pollMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new FlowFailure(FlowFailure.Code.WORKER_INTERRUPTED); }
    }

    private static String now() { return Instant.now().toString(); }
}
