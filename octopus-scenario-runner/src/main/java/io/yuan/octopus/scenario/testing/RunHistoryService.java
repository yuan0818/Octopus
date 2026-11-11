package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Phase 2.5：按测试点查看运行历史，并用启发式规则标记"不稳定"。
 * <p>
 * 不稳定判定（启发式，只用于提示，不改变任何判定）：同一流程修订版、同一测试点最近
 * {@value #WINDOW} 次已结束运行中，PASS 与 FAIL 都出现，且相邻结果翻转至少 {@value #MIN_FLIPS} 次。
 * 由于部署版本不在运行记录中，代码真实变化也可能造成翻转——提示文案会说明这一点。
 */
@Service
public class RunHistoryService {

    static final int WINDOW = 10;
    static final int MIN_FLIPS = 2;
    private static final Set<String> UNFINISHED = Set.of("QUEUED", "RUNNING");

    public record Entry(String runId, String scenarioId, int flowRevision, String state, String verdict, String error,
                        String createdAt, String finishedAt) {}

    public record Flakiness(boolean flaky, int window, int pass, int fail, int inconclusive, int flips) {}

    public record History(String flowId, String testPointId, List<Entry> runs, Map<Integer, Flakiness> byRevision) {}

    private final BusinessTestScenarioRepository scenarios;
    private final BusinessTestRunRepository runs;

    public RunHistoryService(BusinessTestScenarioRepository scenarios, BusinessTestRunRepository runs) {
        this.scenarios = scenarios;
        this.runs = runs;
    }

    public History history(String flowId, String testPointId, int limit) {
        IrValidator.identifier(flowId, "flowId");
        IrValidator.identifier(testPointId, "testPointId");
        require(limit >= 1 && limit <= 200, "limit 需要 1..200");
        Map<String, BusinessTestScenario> byId = new HashMap<>();
        scenarios.findTop500ByFlowIdAndTestPointIdOrderByCreatedAtDesc(flowId, testPointId).forEach(s -> byId.put(s.id, s));
        if (byId.isEmpty()) return new History(flowId, testPointId, List.of(), Map.of());
        List<Entry> entries = new ArrayList<>();
        for (BusinessTestRun run : runs.findTop200ByScenarioIdInOrderByCreatedAtDesc(byId.keySet())) {
            var scenario = byId.get(run.scenarioId);
            entries.add(new Entry(run.id, run.scenarioId, scenario.flowRevision, run.state, run.verdict, run.error,
                    run.createdAt == null ? null : run.createdAt.toString(),
                    run.finishedAt == null ? null : run.finishedAt.toString()));
        }
        Map<Integer, List<Entry>> revisions = new TreeMap<>();
        entries.forEach(e -> revisions.computeIfAbsent(e.flowRevision(), k -> new ArrayList<>()).add(e));
        Map<Integer, Flakiness> flakiness = new TreeMap<>();
        revisions.forEach((rev, list) -> flakiness.put(rev, flakiness(list)));
        return new History(flowId, testPointId, entries.stream().limit(limit).toList(), flakiness);
    }

    /** 某修订版测试点的不稳定判定。 */
    public Flakiness flakiness(String flowId, int flowRevision, String testPointId) {
        var history = history(flowId, testPointId, 200);
        return history.byRevision().getOrDefault(flowRevision, new Flakiness(false, 0, 0, 0, 0, 0));
    }

    /** entries 需按时间倒序。 */
    static Flakiness flakiness(List<Entry> entries) {
        List<String> verdicts = entries.stream()
                .filter(e -> !UNFINISHED.contains(e.state()))
                .map(Entry::verdict)
                .limit(WINDOW)
                .toList();
        int pass = 0, fail = 0, inconclusive = 0, flips = 0;
        String previous = null;
        for (String verdict : verdicts) {
            if ("PASS".equals(verdict)) pass++;
            else if ("FAIL".equals(verdict)) fail++;
            else { inconclusive++; continue; }    // 无法判定不参与翻转计数
            if (previous != null && !previous.equals(verdict)) flips++;
            previous = verdict;
        }
        return new Flakiness(pass > 0 && fail > 0 && flips >= MIN_FLIPS, verdicts.size(), pass, fail, inconclusive, flips);
    }
}
