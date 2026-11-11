package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.scenario.generation.CaseGenerationEngine.*;
import io.yuan.octopus.scenario.testing.*;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 三角色用例生成任务（ADR-CG-01）：持久化、租约、恢复、取消和对外视图。编排逻辑在 {@link CaseGenerationEngine}。
 * 同步模式（旧接口兼容）只运行单轮、probe=OFF 的任务，不落库，仍经过 Planner → Operator → Observer（D-01）。
 */
@Service
public class CaseGenerationService {
    static final long LEASE_MILLIS = 45_000;

    private final CaseGenerationStore store;
    private final CaseGenerationEngine engine;
    private final CaseGenerationEngine syncEngine;
    private final BusinessKnowledgeStore knowledge;
    private final ProbeExecutionService probeExecution;
    private final Budget budget;
    private final ExecutorService pool;
    private final ScheduledExecutorService supervisor;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, String> workers = new ConcurrentHashMap<>();
    private final Map<String, String> dispatched = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    @Autowired
    public CaseGenerationService(CaseGenerationStore store, BusinessKnowledgeStore knowledge, FlowModelClient models, StaticChecks checks,
                                 ProbeExecutionService probeExecution, ProbeEnvironmentService environments,
                                 org.springframework.beans.factory.ObjectProvider<io.yuan.octopus.scenario.extension.FunctionCatalogService> catalogs,
                                 @Value("${scenario.testing.case-generation.max-rounds:3}") int maxRounds,
                                 @Value("${scenario.testing.case-generation.max-probes:30}") int maxProbes,
                                 @Value("${scenario.testing.case-generation.max-probes-per-intent:2}") int perIntent,
                                 @Value("${scenario.testing.case-generation.task-timeout-seconds:1800}") long taskTimeout,
                                 @Value("${scenario.testing.case-generation.probe-timeout-seconds:300}") long probeTimeout,
                                 @Value("${scenario.testing.model.interactive-budget-seconds:240}") long interactiveBudget) {
        this.store = store;
        this.knowledge = knowledge;
        this.probeExecution = probeExecution;
        this.budget = new Budget(maxRounds, maxProbes, perIntent, taskTimeout, probeTimeout);
        var gateway = new ProbeGateway() {
            public ProbeRun submit(ProbeExecutionService.Submission s) { return probeExecution.submit(s); }
            public Optional<ProbeRun> get(String id) { return probeExecution.get(id); }
            public void cancel(String id) { probeExecution.cancel(id); }
            public ProbeEnvironmentService.Active environment(String name) { return environments.active(name); }
        };
        var modelSource = new ModelIntentSource(models);
        var slots = new DataSlotFiller(models, catalogs.getIfAvailable());
        this.engine = new CaseGenerationEngine(knowledge, modelSource, slots, checks, gateway, budget, clock);
        this.syncEngine = new CaseGenerationEngine(knowledge, modelSource, slots, checks, gateway, new Budget(1, 0, 1, Math.min(interactiveBudget, 260), 300), clock);
        this.pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20), Thread.ofPlatform().name("case-generation-", 0).daemon(true).factory());
        this.supervisor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("case-generation-recovery").daemon(true).factory());
        supervisor.scheduleWithFixedDelay(this::maintenance, 5, 5, TimeUnit.SECONDS);
    }

    /** 测试用：同步执行、无后台线程。 */
    CaseGenerationService(CaseGenerationStore store, BusinessKnowledgeStore knowledge, CaseGenerationEngine engine, CaseGenerationEngine syncEngine,
                          ProbeExecutionService probeExecution, Budget budget) {
        this.store = store;
        this.knowledge = knowledge;
        this.engine = engine;
        this.syncEngine = syncEngine;
        this.probeExecution = probeExecution;
        this.budget = budget;
        this.pool = null;
        this.supervisor = null;
    }

    @PreDestroy
    public void close() {
        closed = true;
        if (supervisor != null) supervisor.shutdownNow();
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------ API

    public TaskView create(TaskRequest request, String user) {
        require(request != null && request.requestId() != null && request.requestId().matches("[A-Za-z0-9_-]{16,64}"), "生成任务缺少请求标识");
        CaseGenerationEngine.validate(request);
        var revision = knowledge.flow(request.flowId(), request.flowRevision());
        require("CONFIRMED".equals(revision.status()), "请先确认作为生成依据的流程版本");
        var task = new Task();
        task.id = UUID.randomUUID().toString();
        task.request = request;
        task.requestHash = hash(request);
        task.createdAt = Instant.now().toString();
        task.updatedAt = task.createdAt;
        task.createdBy = user;
        task.flowHash = revision.hash();
        task.event("QUEUED", "任务已保存，等待开始");
        var saved = store.create(task);
        if (saved.state == TaskState.QUEUED) enqueue(saved.id);
        return view(saved);
    }

    public TaskView get(String id) { return view(store.get(id)); }

    public List<TaskSummary> recent(String flowId) { return store.recent(flowId).stream().map(CaseGenerationService::summary).toList(); }

    public TaskView cancel(String id) {
        cancelled.add(id);
        var task = store.update(id, t -> {
            require(!CaseGenerationEngine.terminal(t.state), "任务已结束");
            t.cancelRequested = true;
            if (t.state == TaskState.QUEUED && t.owner == null) {
                t.state = TaskState.CANCELLED;
                t.endReason = "CANCELLED";
                t.event("CANCELLED", "任务已取消");
            } else t.event("CANCEL_REQUESTED", "正在停止：在途探测将按取消规则收尾");
        });
        var round = task.current();
        if (round != null) round.probes.forEach((intent, run) -> { if (!round.observations.containsKey(intent)) probeExecution.cancel(run); });
        return view(task);
    }

    /** 从最近检查点继续（LOOP-005）：已提交的探测只读取，不重新提交。 */
    public TaskView resume(String id) {
        var task = store.update(id, t -> {
            require(t.state == TaskState.FAILED, "只有失败的任务可以继续");
            require(!"TASK_TIMEOUT".equals(t.errorCode), "任务已超过总时限，请重新创建任务");
            t.state = TaskState.QUEUED;
            t.errorCode = null;
            t.error = null;
            t.owner = null;
            t.leaseUntil = 0;
            t.event("QUEUED", "从最近检查点继续");
        });
        enqueue(id);
        return view(task);
    }

    // ------------------------------------------------------------------ worker

    private void enqueue(String id) {
        if (pool == null) { advanceLoop(id); return; }
        String ticket = UUID.randomUUID().toString();
        if (closed || dispatched.putIfAbsent(id, ticket) != null) return;
        try { pool.execute(() -> { try { advanceLoop(id); } finally { dispatched.remove(id, ticket); } }); }
        catch (RejectedExecutionException e) {
            dispatched.remove(id, ticket);
            store.update(id, t -> { if (t.state == TaskState.QUEUED) CaseGenerationEngine.fail(t, "QUEUE_BUSY", "生成队列已满，任务已保存，可稍后继续"); });
        }
    }

    void advanceLoop(String id) {
        if (closed) return;
        String token = UUID.randomUUID().toString();
        var claimed = store.update(id, t -> {
            if (t.state != TaskState.QUEUED) return;
            t.owner = token;
            t.leaseUntil = clock.millis() + LEASE_MILLIS;
            t.state = TaskState.RUNNING;
            t.event("RUNNING", "执行器已接管任务");
        });
        if (!owned(claimed, token)) return;
        workers.put(id, token);
        long[] lastCheck = {0};
        boolean[] remoteCancel = {false};
        try {
            while (!closed) {
                var copy = store.get(id);
                if (!owned(copy, token)) return;
                boolean more;
                try {
                    more = engine.advance(copy, () -> {
                        if (cancelled.contains(id)) return true;
                        if (clock.millis() - lastCheck[0] > 3000) {
                            lastCheck[0] = clock.millis();
                            try { remoteCancel[0] = store.get(id).cancelRequested; } catch (RuntimeException ignored) { }
                        }
                        return remoteCancel[0];
                    });
                } catch (RuntimeException e) {
                    var failure = e instanceof FlowFailure f ? f : null;
                    String code = failure != null ? failure.code.name() : e instanceof IllegalArgumentException ? "INVALID" : "UNKNOWN";
                    String message = failure != null || e instanceof IllegalArgumentException ? e.getMessage() : "生成任务发生未分类错误，已保留最近检查点";
                    LoggerFactory.getLogger(CaseGenerationService.class).warn("Case generation task={} code={}", id, code);
                    store.update(id, t -> { if (owned(t, token)) { mergeSafe(t, copy); CaseGenerationEngine.fail(t, code, message); } });
                    return;
                }
                var written = store.replace(id, current -> {
                    if (!owned(current, token)) return current;
                    copy.cancelRequested |= current.cancelRequested;
                    if (!CaseGenerationEngine.terminal(copy.state)) copy.leaseUntil = Math.max(copy.leaseUntil, current.leaseUntil);
                    return copy;
                });
                if (written != copy || !more) return;
            }
        } finally {
            workers.remove(id, token);
            cancelled.remove(id);
        }
    }

    /** 失败时保留工作单元中已完成的部分（例如已记录的 runId），避免恢复时丢失探测引用。 */
    private static void mergeSafe(Task target, Task work) {
        target.rounds = work.rounds;
        target.round = work.round;
        target.phase = work.phase;
        target.probesUsed = work.probesUsed;
        target.probesPerIntent = work.probesPerIntent;
        target.roleCalls = work.roleCalls;
        target.startedMillis = work.startedMillis;
        target.deadlineMillis = work.deadlineMillis;
    }

    private boolean owned(Task t, String token) {
        return Objects.equals(t.owner, token) && t.state == TaskState.RUNNING && t.leaseUntil > clock.millis();
    }

    void maintenance() {
        if (closed) return;
        workers.forEach((id, token) -> {
            try { store.update(id, t -> { if (Objects.equals(t.owner, token) && t.state == TaskState.RUNNING) t.leaseUntil = clock.millis() + LEASE_MILLIS; }); }
            catch (RuntimeException e) { LoggerFactory.getLogger(CaseGenerationService.class).warn("Case generation heartbeat failed: {}", id); }
        });
        try {
            for (String id : store.pending()) {
                try {
                    var t = store.update(id, task -> {
                        if (task.state == TaskState.RUNNING && task.leaseUntil <= clock.millis() && !workers.containsKey(task.id)) {
                            task.state = TaskState.QUEUED;
                            task.owner = null;
                            task.event("RECOVERED", "执行进程中断或失去租约，从最近检查点自动继续");
                        }
                    });
                    if (t.state == TaskState.QUEUED) enqueue(id);
                } catch (RuntimeException e) { LoggerFactory.getLogger(CaseGenerationService.class).warn("Case generation recovery failed: {}", id); }
            }
        } catch (RuntimeException e) { LoggerFactory.getLogger(CaseGenerationService.class).warn("Case generation recovery scan failed"); }
    }

    // ------------------------------------------------------------------ sync (legacy endpoints)

    /** 旧接口兼容：单轮、probe=OFF，在调用线程内完成，不落库。 */
    public Task runSync(TaskRequest request) {
        var normalized = new TaskRequest(request.requestId() == null ? "sync" : request.requestId(), request.flowId(), request.flowRevision(), request.testPointId(),
                request.strategies(), request.coverage(), request.limit(), request.goal(), request.model(), request.rejection(),
                new ProbeConfig(ProbeMode.OFF, null, null, null, null));
        CaseGenerationEngine.validate(normalized);
        var task = new Task();
        task.id = "sync-" + UUID.randomUUID();
        task.request = normalized;
        task.createdAt = Instant.now().toString();
        int guard = 0;
        while (syncEngine.advance(task, () -> false)) require(++guard < 1000, "生成任务未能结束");
        if (task.state == TaskState.FAILED) throw CaseGenerationEngine.failure(task);
        return task;
    }

    // ------------------------------------------------------------------ apply

    public record ApplyRequest(List<String> intentIds, Map<String, String> decisions, Map<String, Map<String, ExpectationSource>> expectationSources) {}
    public record Skipped(String intentId, String reason) {}
    public record ApplyResult(String flowId, int flowRevision, String flowHash, List<TestPoint> points, List<Skipped> skipped) {}

    /**
     * 返回可加入流程草稿的测试点（由前端加入编辑器，保存为新草稿后再确认）。
     * 副作用未知的候选不能应用；矛盾的候选必须标注“缺陷，保留原预期”或“依据错误，不应用”（OBS-002）。
     */
    public ApplyResult apply(String id, ApplyRequest request) {
        var t = store.get(id);
        require(t.state == TaskState.READY || t.state == TaskState.NEEDS_INPUT, "只有已结束的任务可以应用候选");
        require(request != null && request.intentIds() != null && !request.intentIds().isEmpty() && request.intentIds().size() <= 100, "请选择要应用的候选");
        var round = t.current();
        List<TestPoint> points = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Set<String> observationIds = new HashSet<>();
        t.rounds.forEach(r -> r.observations.values().forEach(o -> observationIds.add(observationId(o))));
        for (String intentId : new LinkedHashSet<>(request.intentIds())) {
            var view = candidate(t, round, intentId);
            if (view == null) { skipped.add(new Skipped(intentId, "候选不存在")); continue; }
            if (view.flags().contains("SIDE_EFFECT_UNKNOWN")) { skipped.add(new Skipped(intentId, "探测副作用未知，请人工核查残留后再应用")); continue; }
            List<String> flags = new ArrayList<>(view.flags());
            if (view.flags().contains("CONTRADICTED")) {
                String decision = request.decisions() == null ? null : request.decisions().get(intentId);
                if ("SOURCE_WRONG".equals(decision)) { skipped.add(new Skipped(intentId, "依据错误：请先修正契约或业务规则再重新生成")); continue; }
                if (!"DEFECT".equals(decision)) { skipped.add(new Skipped(intentId, "请先判断：缺陷（保留原预期）或依据错误（不应用）")); continue; }
                flags.add("SUSPECTED_DEFECT");
            }
            var point = view.point();
            var g = point.generatedFrom();
            Map<String, ExpectationSource> sources = new TreeMap<>(g.expectationSources() == null ? Map.of() : g.expectationSources());
            var overrides = request.expectationSources() == null ? null : request.expectationSources().get(intentId);
            if (overrides != null) for (var e : overrides.entrySet()) {
                var s = e.getValue();
                require(s != null && "HUMAN".equals(s.type()), "人工补充的预期来源只能是 HUMAN");
                require(s.observationId() == null || observationIds.contains(s.observationId()), "引用的观察不属于本任务");
                sources.put(e.getKey(), s);
            }
            List<String> observations = new ArrayList<>();
            for (var r : t.rounds) { var o = r.observations.get(intentId); if (o != null) observations.add(observationId(o)); }
            var generation = new CaseGeneration(g.flowHash(), g.sourceRule(), g.fingerprint(), g.sourcePointId(), t.id, intentId, view.round(),
                    sources.isEmpty() ? null : sources, List.copyOf(new LinkedHashSet<>(observations)), List.copyOf(new LinkedHashSet<>(flags)));
            points.add(new TestPoint(point.id(), point.title(), point.covers(), point.assertions(), point.inputOverrides(), point.intentionallyViolatedRule(),
                    point.endAtStepRef(), point.suppressedOutputCalls(), generation));
        }
        return new ApplyResult(t.request.flowId(), t.request.flowRevision(), t.flowHash, points, skipped);
    }

    static String observationId(Observation o) {
        return o.probeRunId() != null ? "probe:" + o.probeRunId() : "static:" + o.intentId() + ":" + o.round();
    }

    // ------------------------------------------------------------------ views

    public record BudgetView(int roundsUsed, int maxRounds, int probesUsed, int maxProbes, int maxProbesPerIntent, long taskTimeoutSeconds) {}
    public record CandidateView(String intentId, String title, String category, String source, String sourceRule, List<String> targetCallIds,
                                String status, String group, List<String> flags, List<String> reasons, String conclusion, int round,
                                String fingerprint, String probeRunId, String skippedReason, String rationale, TestPoint point) {}
    public record Summary(Map<String, Integer> conclusions, int suspectedDefects, int sideEffectUnknown, int blocked, int ready, int openQuestions) {}
    public record TaskView(String id, String state, String phase, int round, String flowId, int flowRevision, String flowHash, String testPointId,
                           List<Strategy> strategies, String probeMode, String probeEnvironment, String createdAt, String updatedAt, String endReason,
                           String errorCode, String error, boolean cancelRequested, BudgetView budget, List<String> warnings, List<String> questions,
                           List<CandidateView> candidates, Summary summary, List<Event> events, List<RoleCall> roleCalls) {}
    public record TaskSummary(String id, String flowId, int flowRevision, String state, String phase, int round, String probeMode,
                              String createdAt, String updatedAt, String endReason) {}
    public record RoundDetail(int round, CandidateDraft candidate, Observation observation, ProbeRunView probe, String skippedReason, boolean carried) {}
    public record ProbeRunView(String id, String state, String environment, int environmentRevision, String namespace, String cleanupStatus,
                               boolean requestsMayHaveBeenSent, String error, String createdAt, String finishedAt) {}
    public record IntentDetail(TestIntent intent, List<RoundDetail> rounds) {}

    public IntentDetail intent(String id, String intentId) {
        var t = store.get(id);
        TestIntent intent = null;
        List<RoundDetail> rounds = new ArrayList<>();
        for (var r : t.rounds) {
            if (r.plan == null) continue;
            var i = r.plan.intents().stream().filter(x -> x.intentId().equals(intentId)).findFirst().orElse(null);
            if (i == null) continue;
            intent = i;
            String runId = r.probes.get(intentId);
            ProbeRunView probe = runId == null ? null : probeExecution.get(runId).map(CaseGenerationService::probeView).orElse(null);
            rounds.add(new RoundDetail(r.round, r.candidates.get(intentId), r.observations.get(intentId), probe, r.skipped.get(intentId), r.carried.contains(intentId)));
        }
        require(intent != null, "意图不存在");
        return new IntentDetail(intent, rounds);
    }

    static ProbeRunView probeView(ProbeRun r) {
        return new ProbeRunView(r.id, r.state, r.environment, r.probeEnvRevision, r.namespace, r.cleanupStatus, r.requestsMayHaveBeenSent, r.error,
                r.createdAt == null ? null : r.createdAt.toString(), r.finishedAt == null ? null : r.finishedAt.toString());
    }

    TaskView view(Task t) {
        var round = t.current();
        List<CandidateView> candidates = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        if (round != null && round.plan != null) {
            questions.addAll(round.plan.questions());
            for (var intent : round.plan.intents()) {
                var view = candidate(t, round, intent.intentId());
                if (view != null) candidates.add(view);
            }
        }
        Map<String, Integer> conclusions = new TreeMap<>();
        candidates.forEach(c -> { if (c.conclusion() != null) conclusions.merge(c.conclusion(), 1, Integer::sum); });
        var summary = new Summary(conclusions, (int) candidates.stream().filter(c -> c.flags().contains("CONTRADICTED")).count(),
                (int) candidates.stream().filter(c -> c.flags().contains("SIDE_EFFECT_UNKNOWN")).count(),
                (int) candidates.stream().filter(c -> CandidateStatusRule.BLOCKED.equals(c.status())).count(),
                (int) candidates.stream().filter(c -> CandidateStatusRule.READY.equals(c.status())).count(), questions.size());
        var events = t.events.size() > 50 ? t.events.subList(t.events.size() - 50, t.events.size()) : t.events;
        return new TaskView(t.id, t.state.name(), t.phase == null ? null : t.phase.name(), t.round, t.request.flowId(), t.request.flowRevision(), t.flowHash,
                t.request.testPointId(), t.request.strategies(), t.request.probe().mode().name(), t.request.probe().environment(), t.createdAt, t.updatedAt,
                t.endReason, t.errorCode, t.error, t.cancelRequested,
                new BudgetView(t.round, budget.maxRounds(), t.probesUsed, budget.maxProbes(), budget.maxProbesPerIntent(), budget.taskTimeoutSeconds()),
                t.warnings, questions, candidates, summary, List.copyOf(events), List.copyOf(t.roleCalls));
    }

    public static CandidateView candidateView(Task t, RoundRecord round, String intentId) { return candidate(t, round, intentId); }

    static CandidateView candidate(Task t, RoundRecord round, String intentId) {
        if (round == null || round.plan == null) return null;
        var intent = round.plan.intents().stream().filter(i -> i.intentId().equals(intentId)).findFirst().orElse(null);
        var draft = intent == null ? null : round.candidates.get(intentId);
        if (draft == null) return null;
        var observation = round.observations.get(intentId);
        var status = CandidateStatusRule.of(intent, draft, observation);
        List<String> reasons = new ArrayList<>(status.reasons());
        String skippedReason = round.skipped.get(intentId);
        if (skippedReason != null && !reasons.contains("未探测：" + skippedReason)) reasons.add("未探测：" + skippedReason);
        String group = status.flags().contains("SIDE_EFFECT_UNKNOWN") ? "SIDE_EFFECT_UNKNOWN"
                : status.flags().contains("CONTRADICTED") ? "CONTRADICTED"
                : status.flags().contains("EXPECTATION_MISSING") ? "EXPECTATION_MISSING"
                : CandidateStatusRule.READY.equals(status.status()) ? (status.flags().contains("NOT_PROBED") ? "NOT_PROBED" : "READY") : "BLOCKED";
        return new CandidateView(intentId, intent.title(), intent.category(), intent.source().name(), intent.sourceRule(), intent.targetCallIds(),
                status.status(), group, status.flags(), List.copyOf(reasons), observation == null ? null : observation.conclusion().name(), draft.round(),
                draft.fingerprint(), observation == null ? null : observation.probeRunId(), skippedReason, intent.rationale(), draft.point());
    }

    static TaskSummary summary(Task t) {
        return new TaskSummary(t.id, t.request.flowId(), t.request.flowRevision(), t.state.name(), t.phase == null ? null : t.phase.name(), t.round,
                t.request.probe().mode().name(), t.createdAt, t.updatedAt, t.endReason);
    }
}
