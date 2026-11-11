package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.scenario.generation.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 旧接口兼容：LLM 负向测试点草稿改为“单轮、probe=OFF、MODEL 策略”的三角色任务同步执行（D-01）。
 * <ul>
 *   <li><b>只产生草稿</b>：不保存、不确认、不执行；加入流程后仍需人工审阅并确认修订版才能生成场景。</li>
 *   <li><b>结构由平台构造</b>：模型只提供目标调用、输入覆盖值、拒绝状态码与业务断言；截止步骤、覆盖范围、输出抑制按负向规则由代码生成。</li>
 *   <li><b>预期必须有依据</b>（PLAN-002）：状态码须是契约声明的状态；取值断言须由契约的 enum／const 支撑，否则记为缺少依据并 BLOCKED。</li>
 * </ul>
 */
@Service
public class NegativeCaseSuggestionService {

    public record Request(String flowId, int flowRevision, String testPointId, Integer limit, String goal, String model) {}

    public record ModelAssertion(String kind, String path, Object expected) {}

    public record ModelCandidate(String title, String callId, Map<String, Object> overrides, Integer rejectionStatus,
                                 List<ModelAssertion> assertions, String intentionallyViolatedRule, String rationale,
                                 List<String> evidence) {}

    public record ModelOutput(List<ModelCandidate> candidates, List<String> questions) {}

    public record Suggestion(String flowHash, List<CaseCandidateService.Candidate> candidates, List<String> rationales,
                             List<String> questions, String model) {}

    private final SuggestionMetricsService metrics;
    private final CaseGenerationEngine engine;

    /**
     * 同步接口：Planner 的全部模型轮次（含格式修正）共用交互时限，须小于前端 270 秒与 Nginx 300 秒，
     * 超出时以 MODEL_TIMEOUT 结束，而不是在调用方放弃等待后才完成。
     */
    @Autowired
    public NegativeCaseSuggestionService(BusinessKnowledgeStore store, FlowModelClient models, SuggestionMetricsService metrics, ScenarioPlanner planner,
                                         @org.springframework.beans.factory.annotation.Value("${scenario.testing.model.interactive-budget-seconds:240}") long interactiveBudgetSeconds) {
        this.metrics = metrics;
        this.engine = new CaseGenerationEngine(store, new ModelIntentSource(models), new DataSlotFiller(models, null), new StaticChecks(planner, store), null,
                new CaseGenerationEngine.Budget(1, 0, 1, Math.min(interactiveBudgetSeconds, 260), 300), java.time.Clock.systemUTC());
    }

    public NegativeCaseSuggestionService(BusinessKnowledgeStore store, FlowModelClient models, SuggestionMetricsService metrics) {
        this(store, models, metrics, new ScenarioPlanner(store, new IrValidator(new CapabilityRegistry())), 240);
    }

    public Suggestion suggest(Request request) {
        require(request != null, "请求不能为空");
        int limit = request.limit() == null ? 5 : request.limit();
        require(limit >= 1 && limit <= 10, "每次最多建议 10 个负向用例");
        require(request.goal() == null || request.goal().length() <= 4000, "补充说明最多 4000 字");
        long started = System.nanoTime();
        CaseGenerationEngine.Task task;
        try {
            task = CaseCandidateService.sync(engine, new TaskRequest("sync", request.flowId(), request.flowRevision(), request.testPointId(),
                    List.of(Strategy.MODEL), List.of(), limit, request.goal(), request.model(), null, new ProbeConfig(ProbeMode.OFF, null, null, null, null)));
        } catch (FlowFailure failure) {
            if (metrics != null) metrics.record("NEGATIVE_CASES", "FAILED", request.model(), request.flowId(), (System.nanoTime() - started) / 1_000_000, null, null, failure.code.name());
            if (failure.code == FlowFailure.Code.MODEL_RESPONSE_INVALID) throw new IllegalArgumentException("模型输出不符合负向用例格式");
            throw failure;
        }
        var round = task.current();
        List<CaseCandidateService.Candidate> candidates = new ArrayList<>();
        List<String> rationales = new ArrayList<>();
        for (var intent : round.plan.intents()) {
            candidates.add(CaseCandidateService.candidate(task, round, intent));
            rationales.add(intent.rationale() == null ? "" : intent.rationale());
        }
        if (metrics != null) metrics.record("NEGATIVE_CASES", "GENERATED", request.model(), request.flowId(), (System.nanoTime() - started) / 1_000_000,
                null, candidates.size(), null);
        return new Suggestion(task.flowHash, candidates, rationales, round.plan.questions(), request.model());
    }

    static ModelOutput parse(String content) {
        require(content != null && !content.isBlank(), "空响应");
        String json = content.strip();
        if (json.startsWith("```")) {
            int first = json.indexOf('\n'), last = json.lastIndexOf("```");
            require(first > 0 && last > first, "无法解析围栏");
            json = json.substring(first + 1, last).strip();
        }
        require(json.length() <= 100_000, "输出过大");
        try {
            return read(json, ModelOutput.class);
        } catch (com.google.gson.JsonParseException e) {
            throw new IllegalArgumentException("JSON 语法错误");
        }
    }

    /** 与三角色 Planner 的模型来源完全一致的构造：模型候选 → 意图（预期来源校验）→ Operator 物化。 */
    static CaseCandidateService.Candidate build(Flow flow, String flowHash, TestPoint base, List<FlowStep> groups,
                                                Map<String, Operation> operations, ModelCandidate candidate, String model) {
        if (candidate == null) return null;
        var plan = new ModelIntentSource.PlanIntent(null, null, candidate.title(), null, candidate.callId(), candidate.overrides(), null, null,
                candidate.rejectionStatus(), candidate.assertions() == null ? null : candidate.assertions().stream()
                .map(a -> a == null ? null : new ModelIntentSource.ModelAssertion(a.kind(), a.path(), a.expected())).toList(),
                candidate.intentionallyViolatedRule(), candidate.rationale(), candidate.evidence(), null);
        var intent = ModelIntentSource.toIntent(plan, flow, base, groups, operations, null);
        if (intent == null) return null;
        var checked = PlanGuard.checkSources(intent, flow, operations, null);
        intent = intent.withExpectations(checked, intent.issues());
        intent = intent.withId(PlanGuard.intentId(intent));
        var m = IntentMaterializer.materialize(intent, flowHash, base.id(), null, 1, model, Map.of(), Set.of());
        List<String> issues = new ArrayList<>(intent.issues());
        m.issues().forEach(i -> { if (!issues.contains(i)) issues.add(i); });
        String input = intent.variation().inputs().isEmpty() ? null : intent.variation().inputs().keySet().iterator().next();
        if (input == null && candidate.overrides() != null && !candidate.overrides().isEmpty()) input = candidate.overrides().keySet().iterator().next();
        return new CaseCandidateService.Candidate(m.fingerprint(), intent.sourceRule(), candidate.callId(), input,
                issues.isEmpty() ? "NEEDS_REVIEW" : "BLOCKED", List.copyOf(issues), m.point());
    }

    /** 回应字段路径是否在 Schema 中声明（逐级 properties / items）。 */
    static boolean pathDeclared(Object schema, String path) {
        return PlanGuard.declared(schema, path);
    }
}
