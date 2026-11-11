package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import java.util.*;

/**
 * 用例生成三角色（Planner／Operator／Observer）的数据模型。
 * <p>需求：docs/requirements/20261023_case-generation-agents_requirements.md；设计：docs/design/20261026_case-generation-agents_design.md。
 * 预期（expectations）与目标（category、targetCallIds、variation、范围）在首轮产生后不可变（PLAN-006）；
 * 只有 dataPlan、slots、preconditionNotes 可由后续轮次依据 PLANNER 反馈修订。
 */
public final class GenerationModel {
    private GenerationModel() {}

    public enum Strategy { RULE, MODEL }
    public enum ProbeMode { OFF, ON }
    public enum TaskState { QUEUED, RUNNING, CANCEL_REQUESTED, READY, NEEDS_INPUT, FAILED, CANCELLED }
    public enum Phase { PLANNING, MATERIALIZING, PROBING, OBSERVING }
    public enum SourceType { CONTRACT, RULE, HUMAN, MISSING }
    public enum Route { PLANNER, HUMAN }

    /** 断言级观察分类（需求 6.3）。 */
    public enum AssertionClass { MATCHED, CONTRADICTED, NO_EXPECTATION, UNOBSERVABLE, NOT_REACHED }

    /** 候选级结论（需求 6.3）。 */
    public enum Conclusion { CONSISTENT, CONTRADICTED, PRECONDITION_FAILED, INCONCLUSIVE, SIDE_EFFECT_UNKNOWN, NOT_PROBED, STATIC_INVALID }

    /** 反馈类型（设计 5.5）。只有前三类可以路由给 Planner（OBS-006）。 */
    public enum FeedbackType {
        PRECONDITION_FAILED, DATA_INVALID, MATERIALIZATION_ERROR,
        CONTRACT_MISMATCH, FLOW_DEFINITION_MISMATCH, UNOBSERVABLE, SIDE_EFFECT_UNKNOWN, EXPECTATION_MISSING, DEFINITION_INVALID;
        public Route route() {
            return switch (this) {
                case PRECONDITION_FAILED, DATA_INVALID, MATERIALIZATION_ERROR -> Route.PLANNER;
                default -> Route.HUMAN;
            };
        }
    }

    public record Rejection(Integer status, String evidence) {}

    public record ProbeConfig(ProbeMode mode, String environment, Map<String, Object> initialContext,
                              Map<String, String> secretEnvironmentNames, Map<String, Target> services) {
        public ProbeConfig {
            initialContext = initialContext == null ? Map.of() : initialContext;
            secretEnvironmentNames = secretEnvironmentNames == null ? Map.of() : secretEnvironmentNames;
            services = services == null ? Map.of() : services;
        }
        @Override public String toString() { return "ProbeConfig[mode=" + mode + ", environment=" + environment + ", values=redacted]"; }
    }

    public record TaskRequest(String requestId, String flowId, int flowRevision, String testPointId,
                              List<Strategy> strategies, List<String> coverage, int limit, String goal, String model,
                              Rejection rejection, ProbeConfig probe) {
        @Override public String toString() { return "TaskRequest[" + requestId + ", " + flowId + "@" + flowRevision + "]"; }
    }

    /** 来源引用：CONTRACT 为 operationRef#response/&lt;status&gt;[/&lt;path&gt;]；RULE 为 stateModel/&lt;modelId&gt;/&lt;ruleId&gt;；HUMAN 为 point/&lt;id&gt;/&lt;assertionId&gt; 或 request/rejection。 */
    public record Expectation(String callId, Assertion assertion, SourceType sourceType, String sourceRef, Object proposed, String note) {
        public Expectation withMissing(String reason) {
            return new Expectation(callId, new Assertion(assertion.id(), assertion.kind(), assertion.path(), null),
                    SourceType.MISSING, null, assertion.expected() != null ? assertion.expected() : proposed, reason);
        }
    }

    /** 被测意图的焦点输入（正向边界或负向违规）。 */
    public record Variation(String callId, Map<String, Binding> inputs, String rule, boolean negative) {}

    /** 需要 Operator 填充的数据位。 */
    public record Slot(String type, String purpose, Map<String, Object> constraints, boolean uniqueKey) {}

    public record TestIntent(
            String intentId, String parentIntentId, Strategy source, String sourceRule, String title, String category,
            List<String> targetCallIds, Variation variation, String endAtStepRef, List<String> suppressedOutputCalls,
            String intentionallyViolatedRule, List<Expectation> expectations,
            Map<String, Map<String, Binding>> dataPlan, Map<String, Map<String, Slot>> slots, String preconditionNotes,
            String rationale, List<String> issues) {
        public TestIntent {
            targetCallIds = targetCallIds == null ? List.of() : List.copyOf(targetCallIds);
            expectations = expectations == null ? List.of() : List.copyOf(expectations);
            dataPlan = dataPlan == null ? Map.of() : dataPlan;
            slots = slots == null ? Map.of() : slots;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
        public boolean baseline() { return "BASELINE".equals(category); }
        public TestIntent withId(String id) {
            return new TestIntent(id, parentIntentId, source, sourceRule, title, category, targetCallIds, variation, endAtStepRef, suppressedOutputCalls,
                    intentionallyViolatedRule, expectations, dataPlan, slots, preconditionNotes, rationale, issues);
        }
        public TestIntent withMutable(Map<String, Map<String, Binding>> data, Map<String, Map<String, Slot>> slotPlan, String notes, List<String> moreIssues) {
            return new TestIntent(intentId, parentIntentId, source, sourceRule, title, category, targetCallIds, variation, endAtStepRef, suppressedOutputCalls,
                    intentionallyViolatedRule, expectations, data, slotPlan, notes, rationale, moreIssues);
        }
        public TestIntent withExpectations(List<Expectation> values, List<String> moreIssues) {
            return new TestIntent(intentId, parentIntentId, source, sourceRule, title, category, targetCallIds, variation, endAtStepRef, suppressedOutputCalls,
                    intentionallyViolatedRule, values, dataPlan, slots, preconditionNotes, rationale, moreIssues);
        }
    }

    public record Drop(String intentId, String reason) {}
    public record Change(String intentId, List<String> feedbackIds, String description) {}

    public record GenerationPlan(int revision, List<TestIntent> intents, List<Drop> drops, List<Change> changes,
                                 List<String> questions, List<String> warnings, int total, boolean truncated, String promptVersion) {
        public GenerationPlan {
            intents = intents == null ? List.of() : List.copyOf(intents);
            drops = drops == null ? List.of() : List.copyOf(drops);
            changes = changes == null ? List.of() : List.copyOf(changes);
            questions = questions == null ? List.of() : List.copyOf(questions);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record StaticResult(boolean ok, List<String> errors, String irHash, boolean materializationError) {}

    public record CandidateDraft(String intentId, int round, TestPoint point, String fingerprint, StaticResult staticResult,
                                 boolean probeEligible, String probeIneligibleReason, List<String> issues) {}

    public record FeedbackItem(String feedbackId, FeedbackType type, Route route, String intentId, String stepId, String callId,
                               String assertionId, String httpStatus, String platformCode, String message) {}

    public record AssertionResult(String callId, String assertionId, AssertionClass classification, SourceType sourceType) {}

    public record CallResult(String callId, String status, String httpStatus, boolean target, boolean write, boolean outcomeUnknown, String platformCode) {}

    public record Observation(String intentId, int round, String source, String probeRunId, Conclusion conclusion,
                              List<AssertionResult> assertions, List<CallResult> calls, List<FeedbackItem> feedback,
                              List<Map<String, Object>> evidence, String cleanupStatus) {}

    /** 给 Planner（模型）的反馈视图：只含分类、定位和错误码，绝不含实际值（ADR-CG-06）。 */
    public record FeedbackView(String feedbackId, String type, String intentId, String stepId, String callId, String assertionId,
                               String httpStatus, String platformCode) {
        static FeedbackView of(FeedbackItem f) {
            return new FeedbackView(f.feedbackId(), f.type().name(), f.intentId(), f.stepId(), f.callId(), f.assertionId(), f.httpStatus(), f.platformCode());
        }
    }

    /** 候选状态（沿用基线：BLOCKED／READY_FOR_REVIEW）及原因。 */
    public record CandidateStatus(String status, List<String> reasons, List<String> flags) {}
}
