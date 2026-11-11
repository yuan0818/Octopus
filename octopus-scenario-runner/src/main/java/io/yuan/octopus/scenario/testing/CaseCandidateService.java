package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.generation.GenerationModel.*;

import io.yuan.octopus.scenario.generation.CaseGenerationEngine;
import io.yuan.octopus.scenario.generation.CaseGenerationService;
import io.yuan.octopus.scenario.generation.StaticChecks;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 旧接口兼容（设计第 7 章）：规则候选生成改为“单轮、probe=OFF”的三角色任务同步执行（D-01）。
 * 响应结构不变；候选仍需人工确认，生成不改变已确认修订、不调用被测接口。
 */
@Service
public class CaseCandidateService {
    public record Request(String flowId,int flowRevision,String testPointId,int limit,Integer rejectionStatus,String rejectionEvidence) {}
    public record Candidate(String fingerprint,String sourceRule,String callId,String input,String status,List<String> issues,TestPoint point) {}
    public record Result(String flowHash,List<Candidate> candidates,int total,boolean truncated,List<String> warnings) {}
    private final CaseGenerationEngine engine;

    public CaseCandidateService(BusinessKnowledgeStore store) {
        this(store, new ScenarioPlanner(store, new IrValidator(new CapabilityRegistry())));
    }

    @Autowired
    public CaseCandidateService(BusinessKnowledgeStore store, ScenarioPlanner planner) {
        this.engine = new CaseGenerationEngine(store, null, null, new StaticChecks(planner, store), null,
                new CaseGenerationEngine.Budget(1, 0, 1, 1800, 300), java.time.Clock.systemUTC());
    }

    public Result generate(Request request) {
        IrValidator.require(request.limit()>=1 && request.limit()<=100,"每批最多生成 100 个候选");
        var rejection = request.rejectionStatus()==null ? null : new Rejection(request.rejectionStatus(), request.rejectionEvidence());
        var task = sync(engine, new TaskRequest("sync", request.flowId(), request.flowRevision(), request.testPointId(), List.of(Strategy.RULE), List.of(),
                request.limit(), null, null, rejection, new ProbeConfig(ProbeMode.OFF, null, null, null, null)));
        var round = task.current();
        List<Candidate> candidates = new ArrayList<>();
        for (var intent : round.plan.intents()) candidates.add(candidate(task, round, intent));
        return new Result(task.flowHash, candidates, round.plan.total(), round.plan.truncated(),
                round.plan.warnings().stream().filter(w -> !w.startsWith("本次规则范围内共")).toList());
    }

    static CaseGenerationEngine.Task sync(CaseGenerationEngine engine, TaskRequest request) {
        CaseGenerationEngine.validate(request);
        var task = new CaseGenerationEngine.Task();
        task.id = "sync-" + UUID.randomUUID();
        task.request = request;
        task.createdAt = java.time.Instant.now().toString();
        int guard = 0;
        while (engine.advance(task, () -> false)) IrValidator.require(++guard < 1000, "生成任务未能结束");
        if (task.state == TaskState.FAILED) throw CaseGenerationEngine.failure(task);
        return task;
    }

    static Candidate candidate(CaseGenerationEngine.Task task, CaseGenerationEngine.RoundRecord round, TestIntent intent) {
        var view = CaseGenerationService.candidateView(task, round, intent.intentId());
        String callId = intent.variation() == null ? null : intent.variation().callId();
        String input = intent.variation() == null || intent.variation().inputs().isEmpty() ? null : intent.variation().inputs().keySet().iterator().next();
        String status = "READY_FOR_REVIEW".equals(view.status()) ? "NEEDS_REVIEW" : "BLOCKED";
        var reasons = view.reasons().stream().filter(r -> !r.startsWith("未探测：")).toList();
        List<String> issues = reasons.isEmpty() && intent.baseline() ? List.of(io.yuan.octopus.scenario.generation.RuleIntentSource.BASELINE_NOTE) : reasons;
        return new Candidate(view.fingerprint(), intent.source() == Strategy.MODEL ? truncate(intent.sourceRule()) : intent.sourceRule(), callId, input, status, issues, view.point());
    }

    private static String truncate(String value) { return value.length() <= 200 ? value : value.substring(0, 200) + "…"; }

}
