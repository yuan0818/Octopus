package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;

import java.util.*;

/** 需求 6.3 候选状态表的纯函数实现：静态结果 + 观察结论 → BLOCKED／READY_FOR_REVIEW。探测一致也不等于确认。 */
public final class CandidateStatusRule {
    private CandidateStatusRule() {}

    public static final String BLOCKED = "BLOCKED";
    public static final String READY = "READY_FOR_REVIEW";

    public static CandidateStatus of(TestIntent intent, CandidateDraft draft, Observation observation) {
        List<String> reasons = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        boolean blocked = false;
        Conclusion conclusion = observation == null ? Conclusion.NOT_PROBED : observation.conclusion();
        if (!draft.staticResult().ok()) {
            blocked = true;
            reasons.addAll(draft.staticResult().errors());
            flags.add("STATIC_INVALID");
        }
        switch (conclusion) {
            case SIDE_EFFECT_UNKNOWN -> { blocked = true; flags.add("SIDE_EFFECT_UNKNOWN"); reasons.add("探测中存在结果未知的写调用或清理未完成，请人工核查残留"); }
            case CONTRADICTED -> { blocked = true; flags.add("CONTRADICTED"); reasons.add("实际行为与预期依据不符：请判断是缺陷还是依据错误"); }
            case PRECONDITION_FAILED -> { blocked = true; flags.add("PRECONDITION_FAILED"); reasons.add("前置调用失败，未能验证目标调用"); }
            case INCONCLUSIVE -> flags.add("NO_PROBE_EVIDENCE");
            case NOT_PROBED -> flags.add("NOT_PROBED");
            case CONSISTENT -> flags.add("PROBE_CONSISTENT");
            case STATIC_INVALID -> { blocked = true; if (!flags.contains("STATIC_INVALID")) flags.add("STATIC_INVALID"); }
        }
        if (intent.expectations().stream().anyMatch(e -> e.sourceType() == SourceType.MISSING)) { blocked = true; flags.add("EXPECTATION_MISSING"); }
        for (String issue : draft.issues()) { blocked = true; reasons.add(issue.startsWith("LEAK:") ? issue.substring(5) : issue); }
        for (String issue : intent.issues()) { blocked = true; if (!reasons.contains(issue)) reasons.add(issue); }
        if (draft.probeIneligibleReason() != null && conclusion == Conclusion.NOT_PROBED) reasons.add("未探测：" + draft.probeIneligibleReason());
        return new CandidateStatus(blocked ? BLOCKED : READY, List.copyOf(new LinkedHashSet<>(reasons)), List.copyOf(new LinkedHashSet<>(flags)));
    }
}
