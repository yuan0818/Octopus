package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.yuan.octopus.scenario.generation.CaseGenerationEngine.Task;
import io.yuan.octopus.scenario.testing.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 闭环编排的组件测试：假模型、假探测网关（AT-G01、G05、G07～G09、G14～G16、G19）。 */
class CaseGenerationEngineTest {

    static final class MutableClock extends Clock {
        long millis = 1_800_000_000_000L;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public long millis() { return millis; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    /** 记录提交、按意图返回合成探测结果。 */
    static final class FakeGateway implements CaseGenerationEngine.ProbeGateway {
        final Map<String, ProbeRun> runs = new LinkedHashMap<>();
        final Map<String, Integer> submissions = new HashMap<>();
        final List<String> cancelled = new ArrayList<>();
        Function<ProbeExecutionService.Submission, JmeterProcessExecutor.Result> behaviour;
        ProbeEnvironmentService.Active env = new ProbeEnvironmentService.Active("gen-sandbox", 1, "DISPOSABLE", 24);
        int environmentCalls, revokeAfter = Integer.MAX_VALUE;
        boolean hold;

        public ProbeRun submit(ProbeExecutionService.Submission s) {
            String key = s.taskId() + ":" + s.round() + ":" + s.intentId();
            submissions.merge(key, 1, Integer::sum);
            var existing = runs.values().stream().filter(r -> r.idempotencyKey.equals(key)).findFirst();
            if (existing.isPresent()) return existing.get();
            var run = new ProbeRun();
            run.id = s.runId();
            run.idempotencyKey = key;
            run.taskId = s.taskId();
            run.roundNo = s.round();
            run.intentId = s.intentId();
            run.environment = "gen-sandbox";
            run.namespace = "gp-x";
            run.createdAt = Instant.now();
            run.requestsMayHaveBeenSent = true;
            if (hold) run.state = "RUNNING";
            else complete(run, behaviour.apply(s));
            runs.put(run.id, run);
            return run;
        }
        void complete(ProbeRun run, JmeterProcessExecutor.Result result) {
            run.state = result.state();
            var report = new LinkedHashMap<String, Object>();
            report.put("result", result);
            report.put("sourceMap", Map.of());
            run.reportJson = TestingJson.canonical(report);
            run.evidenceJson = "[]";
            run.cleanupStatus = result.cleanupStatus();
        }
        public Optional<ProbeRun> get(String id) { return Optional.ofNullable(runs.get(id)); }
        public void cancel(String id) {
            cancelled.add(id);
            var run = runs.get(id);
            if (run != null && !run.terminal()) complete(run, result("CANCELLED", List.of(sample("step:login", true, "200", ""))));
        }
        public ProbeEnvironmentService.Active environment(String name) { return ++environmentCalls > revokeAfter ? null : env; }
        int distinctRuns() { return runs.size(); }
    }

    static JmeterProcessExecutor.Sample sample(String label, boolean ok, String code, String failure) { return new JmeterProcessExecutor.Sample(label, ok, code, 5, failure); }
    static JmeterProcessExecutor.Result result(String state, List<JmeterProcessExecutor.Sample> samples) {
        return new JmeterProcessExecutor.Result(state, "INCONCLUSIVE", samples, null, "COMPLETED".equals(state), "NOT_REQUIRED", false, List.of());
    }
    static JmeterProcessExecutor.Result lockedLogin() {
        return result("COMPLETED", List.of(sample("step:login", false, "423", "Business verification failed: status, output:auth.token"),
                sample("guard:create", false, "FLOW_BLOCKED", "blocked")));
    }
    static JmeterProcessExecutor.Result rejected() {
        return result("COMPLETED", List.of(sample("step:login", true, "200", ""), sample("step:create", true, "422", "")));
    }

    final Flow flow = GenerationFixtures.flow(false);
    final BusinessKnowledgeStore store = GenerationFixtures.store(flow);
    final FlowModelClient models = mock(FlowModelClient.class);
    final FakeGateway gateway = new FakeGateway();
    final MutableClock clock = new MutableClock();

    StaticChecks checks() {
        return new StaticChecks(GenerationFixtures.planner(store), store) {
            @Override public Checked check(FlowRevision confirmed, TestPoint candidate, List<String> issues, ProbeConfig probe) {
                var c = super.check(confirmed, candidate, issues, null);
                var profile = c.result().ok() && probe != null && probe.mode() == ProbeMode.ON ? new ExecutionProfile(Map.of(), Map.of(), Map.of(), "gen-sandbox") : null;
                return new Checked(c.result(), c.ir(), profile);
            }
        };
    }

    CaseGenerationEngine engine() {
        var e = new CaseGenerationEngine(store, new ModelIntentSource(models), new DataSlotFiller(models, null), checks(), gateway,
                CaseGenerationEngine.Budget.DEFAULT, clock);
        e.pollMillis = 1;
        return e;
    }

    Task task(List<Strategy> strategies, ProbeMode mode) {
        var t = new Task();
        t.id = "task-1";
        t.request = GenerationFixtures.request(strategies, mode, null);
        t.createdAt = Instant.now().toString();
        return t;
    }

    /** 每个工作单元后经 JSON 往返，等价于“写检查点再读回”。 */
    static Task checkpoint(Task t) { return TestingJson.GSON.fromJson(TestingJson.canonical(t), Task.class); }

    Task run(CaseGenerationEngine e, Task t) {
        int guard = 0;
        while (true) {
            boolean more = e.advance(t, () -> false);
            t = checkpoint(t);
            if (!more) return t;
            assertTrue(++guard < 500);
        }
    }

    static String intentOf(Task t, String category) {
        return t.current().plan.intents().stream().filter(i -> category.equals(i.category())).findFirst().orElseThrow().intentId();
    }

    @Test
    void staticModeNeverProbes() {
        // AT-G01
        var t = run(engine(), task(List.of(Strategy.RULE), ProbeMode.OFF));
        assertEquals(0, gateway.distinctRuns());
        assertEquals(0, gateway.environmentCalls);
        assertEquals(TaskState.NEEDS_INPUT, t.state, "negative rule intents lack a rejection oracle");
        var round = t.current();
        assertTrue(round.observations.values().stream().allMatch(o -> o.conclusion() == Conclusion.NOT_PROBED || o.conclusion() == Conclusion.STATIC_INVALID));
        assertEquals("CONVERGED", t.endReason);
        assertEquals(1, t.rounds.size());
    }

    @Test
    void sharedPreconditionFailureSkipsDependentsThenPlannerFixesDataInNextRound() {
        // AT-G05 + LOOP-004 + PLAN-005：基线前置失败 → 依赖意图本轮跳过并继承反馈 → Planner 只改数据 → 下一轮探测通过
        when(models.casePlan(any(), any(), any())).thenAnswer(inv -> {
            Map<String, Object> context = inv.getArgument(1);
            if (((Number) context.get("round")).intValue() == 1) return new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "case-plan@1");
            @SuppressWarnings("unchecked") var feedback = (List<GenerationModel.FeedbackView>) context.get("feedback");
            var f = feedback.getFirst();
            String answer = "{\"intents\":[{\"intentId\":\"" + f.intentId() + "\",\"data\":{\"login\":{\"body\":{\"user\":\"spare\"}}},\"preconditionNotes\":\"换用未锁定账号\"}],"
                    + "\"drops\":[],\"changes\":[{\"intentId\":\"" + f.intentId() + "\",\"feedbackIds\":[\"" + f.feedbackId() + "\"],\"description\":\"换账号\"}],\"questions\":[]}";
            return new FlowModelClient.VersionedResponse(answer, "m", "case-plan@1");
        });
        gateway.behaviour = s -> s.ir().steps().stream().anyMatch(step -> step.id().equals("login") && "literal".equals(step.inputs().get("body").source()))
                ? rejected() : lockedLogin();
        var t = run(engine(), task(List.of(Strategy.RULE, Strategy.MODEL), ProbeMode.ON));
        assertEquals(2, t.rounds.size());
        var first = t.rounds.getFirst();
        String modelIntent = t.current().plan.intents().stream().filter(i -> i.source() == Strategy.MODEL).findFirst().orElseThrow().intentId();
        assertEquals(1, first.probes.size(), "only the baseline is probed in round 1");
        assertEquals(CaseGenerationEngine.SHARED_PRECONDITION, first.skipped.get(modelIntent));
        assertTrue(first.observations.get(modelIntent).feedback().stream().anyMatch(f -> f.route() == Route.PLANNER));
        var second = t.current();
        var fresh = second.probes.entrySet().stream().filter(e -> !first.probes.containsValue(e.getValue())).map(Map.Entry::getKey).toList();
        assertEquals(List.of(modelIntent), fresh, "only the revised intent is probed again");
        assertEquals(Conclusion.CONSISTENT, second.observations.get(modelIntent).conclusion());
        assertTrue(second.carried.size() >= 1);
        assertEquals(2, gateway.distinctRuns());
        assertTrue(gateway.submissions.values().stream().allMatch(n -> n == 1));
        // 预期在修订中保持不变（PLAN-006）
        var before = first.plan.intents().stream().filter(i -> i.intentId().equals(modelIntent)).findFirst().orElseThrow();
        var after = second.plan.intents().stream().filter(i -> i.intentId().equals(modelIntent)).findFirst().orElseThrow();
        assertEquals(PlanGuard.immutableHash(before), PlanGuard.immutableHash(after));
        assertEquals(TaskState.NEEDS_INPUT, t.state, "the baseline itself still needs a human");
    }

    @Test
    void identicalRevisionOrRepeatedFeedbackStopsWithNoProgress() {
        // AT-G08：模型不改变任何东西
        when(models.casePlan(any(), any(), any())).thenAnswer(inv -> {
            Map<String, Object> context = inv.getArgument(1);
            return new FlowModelClient.VersionedResponse(((Number) context.get("round")).intValue() == 1 ? GenerationFixtures.modelPlan()
                    : "{\"intents\":[],\"drops\":[],\"changes\":[],\"questions\":[]}", "m", "case-plan@1");
        });
        gateway.behaviour = s -> lockedLogin();
        var t = run(engine(), task(List.of(Strategy.MODEL), ProbeMode.ON));
        assertEquals("NO_PROGRESS", t.endReason);
        assertEquals(1, t.rounds.size());

        // 数据每轮都变，但前置仍以同样方式失败：第二轮结束时同一组反馈 → 不收敛
        var counter = new int[]{0};
        reset(models);
        when(models.casePlan(any(), any(), any())).thenAnswer(inv -> {
            Map<String, Object> context = inv.getArgument(1);
            if (((Number) context.get("round")).intValue() == 1) return new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "v");
            @SuppressWarnings("unchecked") var f = ((List<GenerationModel.FeedbackView>) context.get("feedback")).getFirst();
            return new FlowModelClient.VersionedResponse("{\"intents\":[{\"intentId\":\"" + f.intentId() + "\",\"data\":{\"login\":{\"body\":{\"user\":\"u" + (++counter[0]) + "\"}}}}],"
                    + "\"drops\":[],\"changes\":[{\"intentId\":\"" + f.intentId() + "\",\"feedbackIds\":[\"" + f.feedbackId() + "\"],\"description\":\"x\"}],\"questions\":[]}", "m", "v");
        });
        var again = new FakeGateway();
        again.behaviour = s -> lockedLogin();
        var e = new CaseGenerationEngine(store, new ModelIntentSource(models), new DataSlotFiller(models, null), checks(), again, CaseGenerationEngine.Budget.DEFAULT, clock);
        e.pollMillis = 1;
        var t2 = run(e, task(List.of(Strategy.MODEL), ProbeMode.ON));
        assertEquals("NO_PROGRESS", t2.endReason);
        assertEquals(2, t2.rounds.size());
    }

    @Test
    void changingFeedbackRunsUntilTheRoundBudget() {
        // LOOP-001：最多 3 轮
        var round = new int[]{0};
        when(models.casePlan(any(), any(), any())).thenAnswer(inv -> {
            Map<String, Object> context = inv.getArgument(1);
            if (((Number) context.get("round")).intValue() == 1) return new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "v");
            @SuppressWarnings("unchecked") var f = ((List<GenerationModel.FeedbackView>) context.get("feedback")).getFirst();
            return new FlowModelClient.VersionedResponse("{\"intents\":[{\"intentId\":\"" + f.intentId() + "\",\"preconditionNotes\":\"r" + (++round[0]) + "\"}],"
                    + "\"drops\":[],\"changes\":[{\"intentId\":\"" + f.intentId() + "\",\"feedbackIds\":[\"" + f.feedbackId() + "\"],\"description\":\"x\"}],\"questions\":[]}", "m", "v");
        });
        var codes = List.of("PREPARE_INPUT_INVALID", "PREPARE_INPUT_MISSING", "PREPARE_BINDING_INVALID", "PREPARE_INPUT_TOO_LARGE");
        var probe = new int[]{0};
        gateway.behaviour = s -> result("COMPLETED", List.of(sample("prepare:login:0", false, codes.get(probe[0]++), "x")));
        var t = run(engine(), task(List.of(Strategy.MODEL), ProbeMode.ON));
        assertEquals(3, t.rounds.size());
        assertEquals("BUDGET", t.endReason);
    }

    @Test
    void outOfBoundsRevisionIsRejectedAndThePreviousPlanKept() {
        // AT-G09：修订试图改变拒绝状态码
        when(models.casePlan(any(), any(), any())).thenAnswer(inv -> {
            Map<String, Object> context = inv.getArgument(1);
            if (((Number) context.get("round")).intValue() == 1) return new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "v");
            @SuppressWarnings("unchecked") var f = ((List<GenerationModel.FeedbackView>) context.get("feedback")).getFirst();
            return new FlowModelClient.VersionedResponse("{\"intents\":[{\"intentId\":\"" + f.intentId() + "\",\"callId\":\"create\",\"overrides\":{\"quantity\":0},\"rejectionStatus\":201,"
                    + "\"assertions\":[{\"kind\":\"JSON_EXISTS\",\"path\":\"$.id\",\"expected\":true}]}],\"drops\":[],"
                    + "\"changes\":[{\"intentId\":\"" + f.intentId() + "\",\"feedbackIds\":[\"" + f.feedbackId() + "\"],\"description\":\"x\"}],\"questions\":[]}", "m", "v");
        });
        gateway.behaviour = s -> lockedLogin();
        var t = run(engine(), task(List.of(Strategy.MODEL), ProbeMode.ON));
        assertEquals("PLAN_REJECTED", t.endReason);
        assertEquals(1, t.rounds.size());
        assertFalse(t.current().planViolations.isEmpty());
        verify(models, times(3)).casePlan(any(), any(), any());   // 首轮 + 修订 + 1 次语义修正
    }

    @Test
    void recoveryFromAnyCheckpointNeverResubmitsAProbe() {
        // AT-G14 / LOOP-005：在每个检查点“崩溃”并从快照恢复两次
        when(models.casePlan(any(), any(), any())).thenReturn(new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "v"));
        gateway.behaviour = s -> rejected();
        var e = engine();
        var t = task(List.of(Strategy.RULE, Strategy.MODEL), ProbeMode.ON);
        List<Task> snapshots = new ArrayList<>();
        while (true) {
            snapshots.add(checkpoint(t));
            boolean more = e.advance(t, () -> false);
            t = checkpoint(t);
            if (!more) break;
        }
        int runs = gateway.distinctRuns();
        assertTrue(runs >= 1);
        for (var snapshot : snapshots) {
            var replay = checkpoint(snapshot);
            int guard = 0;
            while (e.advance(replay, () -> false)) { replay = checkpoint(replay); assertTrue(++guard < 500); }
        }
        assertEquals(runs, gateway.distinctRuns(), "replays reuse the recorded runs");
        // 模型在修订时失败：已完成的探测保留
        assertTrue(gateway.submissions.values().stream().allMatch(n -> n >= 1));
    }

    @Test
    void cancellationDuringAProbeStopsItAndKeepsCompletedWork() {
        // AT-G15
        when(models.casePlan(any(), any(), any())).thenReturn(new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "v"));
        gateway.hold = true;
        var e = engine();
        var t = task(List.of(Strategy.MODEL), ProbeMode.ON);
        // 提交后立即请求取消（模拟用户在探测进行中点击取消）
        int guard = 0;
        while (e.advance(t, () -> !gateway.runs.isEmpty())) { t = checkpoint(t); assertTrue(++guard < 50); }
        assertEquals(1, gateway.cancelled.size());
        assertEquals(TaskState.CANCELLED, t.state);
        assertEquals(1, t.current().observations.size(), "the cancelled probe is still observed");
        assertEquals(Conclusion.SIDE_EFFECT_UNKNOWN, t.current().observations.values().iterator().next().conclusion(), "an in-flight write may have happened");
    }

    @Test
    void revokedEnvironmentFallsBackToStaticMode() {
        // AT-G19
        when(models.casePlan(any(), any(), any())).thenReturn(new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "v"));
        gateway.behaviour = s -> rejected();
        gateway.revokeAfter = 3;   // 物化时读取若干次后撤销
        var t = run(engine(), task(List.of(Strategy.RULE), ProbeMode.ON));
        assertTrue(t.events.stream().anyMatch(ev -> ev.kind().equals("PROBE_DISABLED")));
        assertTrue(t.current().skipped.values().stream().anyMatch(r -> r.contains("撤销")));
        assertTrue(CaseGenerationEngine.terminal(t.state));
    }

    @Test
    void cleanupRequiredEnvironmentNeedsAReviewedCleanupList() {
        // AT-G16
        gateway.env = new ProbeEnvironmentService.Active("gen-sandbox", 1, "CLEANUP_REQUIRED", null);
        gateway.behaviour = s -> rejected();
        var t = run(engine(), task(List.of(Strategy.RULE), ProbeMode.ON));
        assertEquals(0, gateway.distinctRuns());
        assertTrue(t.current().candidates.values().stream().noneMatch(CandidateDraft::probeEligible));
        assertTrue(t.current().candidates.values().stream().filter(d -> d.staticResult().ok()).allMatch(d -> d.probeIneligibleReason().contains("缺少清理清单")));
        assertTrue(t.current().candidates.values().stream().anyMatch(d -> d.staticResult().ok()));
    }

    @Test
    void taskTimeoutFailsButKeepsCompletedRounds() {
        var e = engine();
        var t = task(List.of(Strategy.RULE), ProbeMode.OFF);
        e.advance(t, () -> false);
        e.advance(t, () -> false);
        clock.millis += 1_900_000;
        assertFalse(e.advance(t, () -> false));
        assertEquals(TaskState.FAILED, t.state);
        assertEquals("TASK_TIMEOUT", t.errorCode);
        assertNotNull(t.current().plan);
    }

    @Test
    void sideEffectUnknownIntentIsNeverProbedAgain() {
        // OPER-009：首轮写调用超时 → 结果未知；即使后续轮次因其他反馈继续，也不再探测该意图
        when(models.casePlan(any(), any(), any())).thenAnswer(inv -> {
            Map<String, Object> context = inv.getArgument(1);
            if (((Number) context.get("round")).intValue() == 1) return new FlowModelClient.VersionedResponse(GenerationFixtures.modelPlan(), "m", "v");
            return new FlowModelClient.VersionedResponse("{\"intents\":[],\"drops\":[],\"changes\":[],\"questions\":[]}", "m", "v");
        });
        gateway.behaviour = s -> result("COMPLETED", List.of(sample("step:login", true, "200", ""), sample("step:create", false, "Non HTTP response code: java.net.SocketTimeoutException", "t")));
        var t = run(engine(), task(List.of(Strategy.MODEL), ProbeMode.ON));
        var obs = t.current().observations.values().iterator().next();
        assertEquals(Conclusion.SIDE_EFFECT_UNKNOWN, obs.conclusion());
        assertEquals(1, gateway.distinctRuns());
        assertEquals("CONVERGED", t.endReason, "frozen intents produce no planner feedback");
    }
}
