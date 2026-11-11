package io.yuan.octopus.scenario.testing;

import java.util.List;
import java.util.Map;

/** Business semantics only. Execution-framework classes belong to the compiler package. */
public final class TestingModel {
    private TestingModel() {}

    public record Implementation(
            long projectId, String branch, String commitId, String signature) {}

    public record Source(String kind, String location, String digest) {}

    public record Port(
            String location, String name, String type, boolean required, String prefix) {}

    public record Operation(
            String operationId,
            String revision,
            String system,
            String service,
            String method,
            String path,
            Map<String, Port> inputs,
            Map<String, Object> requestSchema,
            Map<String, Object> responseSchema,
            List<Implementation> implementations,
            Source source,
            List<String> requiresCapabilities,
            String securityProfile) {
        public String ref() {
            return operationId + "@" + revision;
        }
    }

    public record Variable(String type, boolean sensitive) {}

    /** source is literal, context, or secret; value is data, never executable code. */
    public record Binding(String source, Object value) {}

    public record Output(String key, String path, String type, boolean sensitive) {}

    public record Assertion(String id, String kind, String path, Object expected) {}

    public record Transition(String ruleId, String from, String to) {}

    public record StateModel(
            String modelId,
            String revision,
            String entityType,
            List<String> states,
            List<Transition> transitions,
            String origin) {}

    public record StateConstraint(
            String modelId,
            String ruleId,
            String entityContext,
            String observedContext,
            String afterPath,
            String observedEntityContext,
            String afterEntityPath) {}

    /**
     * 轮询（Phase 2.1）：重复发送同一请求，直到该步骤的全部检查项通过或超时。
     * 用于异步接口（提交后查询状态），取代固定等待。中间轮次不提交上下文、不判失败；
     * 只有最后一轮（成功或超时）按普通语义判定。
     * <p>FR-POLL：只允许对使用者显式授权为可轮询的 API 使用；{@code authorizationRef} 固定所用授权
     * （{@code 授权ID@修订号}），为空表示旧流程（运行前按当前有效授权核对）。
     */
    public record Poll(long intervalMillis, long timeoutMillis,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String authorizationRef) {
        /** Phase 2.1 form without an authorization reference; runs only if the API is authorized (FR-POLL-06). */
        public Poll(long intervalMillis, long timeoutMillis) { this(intervalMillis, timeoutMillis, null); }
    }

    public record FlowNode(
            String id,
            String label,
            String operationRef,
            Map<String, Binding> inputs,
            List<Output> outputs,
            List<StateConstraint> states,
            long waitMillis,
            io.yuan.octopus.preparation.PreparationPlan preparation,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Poll poll) {
        public FlowNode(String id,String label,String operationRef,Map<String,Binding> inputs,List<Output> outputs,List<StateConstraint> states,long waitMillis,
                        io.yuan.octopus.preparation.PreparationPlan preparation) {
            this(id,label,operationRef,inputs,outputs,states,waitMillis,preparation,null);
        }
        public FlowNode(String id,String label,String operationRef,Map<String,Binding> inputs,List<Output> outputs,List<StateConstraint> states,long waitMillis) {
            this(id,label,operationRef,inputs,outputs,states,waitMillis,null);
        }
    }

    public record Dependency(String from, String to, String kind, String reason) {}

    /**
     * Assertions are an explicit independent oracle; overrides describe deliberate negative inputs.
     */
    /**
     * 候选来源与追溯。taskId 起的字段由三角色生成任务填写（TRACE-001），旧数据与旧 hash 不受影响（null 时不序列化）。
     * expectationSources：assertionId → 来源（CONTRACT／RULE／HUMAN，可带人工参照的 observationId，OBS-004）。
     */
    public record CaseGeneration(String flowHash,String sourceRule,String fingerprint,String sourcePointId,
                                 String taskId,String intentId,Integer round,
                                 Map<String,ExpectationSource> expectationSources,List<String> observationIds,List<String> flags) {
        public CaseGeneration(String flowHash,String sourceRule,String fingerprint,String sourcePointId) {
            this(flowHash,sourceRule,fingerprint,sourcePointId,null,null,null,null,null,null);
        }
    }
    public record ExpectationSource(String type,String ref,String observationId) {}
    public record TestPoint(
            String id,
            String title,
            List<String> covers,
            Map<String, List<Assertion>> assertions,
            Map<String, Map<String, Binding>> inputOverrides,
            String intentionallyViolatedRule,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String endAtStepRef,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> suppressedOutputCalls,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CaseGeneration generatedFrom) {
        public TestPoint(String id,String title,List<String> covers,Map<String,List<Assertion>> assertions,
                         Map<String,Map<String,Binding>> inputOverrides,String intentionallyViolatedRule,String endAtStepRef,List<String> suppressedOutputCalls) {
            this(id,title,covers,assertions,inputOverrides,intentionallyViolatedRule,endAtStepRef,suppressedOutputCalls,null);
        }
        public TestPoint(String id,String title,List<String> covers,Map<String,List<Assertion>> assertions,
                         Map<String,Map<String,Binding>> inputOverrides,String intentionallyViolatedRule,String endAtStepRef) {
            this(id,title,covers,assertions,inputOverrides,intentionallyViolatedRule,endAtStepRef,null);
        }
        public TestPoint(String id, String title, List<String> covers, Map<String,List<Assertion>> assertions,
                         Map<String,Map<String,Binding>> inputOverrides, String intentionallyViolatedRule) {
            this(id,title,covers,assertions,inputOverrides,intentionallyViolatedRule,null);
        }
    }

    public record GroupAssertion(String id, String leftKey, String rightKey) {}
    public record FlowStep(String id, String label, String mode, List<FlowNode> calls, List<GroupAssertion> assertions) {
        public FlowStep(String id,String label,String mode,List<FlowNode> calls) {this(id,label,mode,calls,List.of());}
        public FlowStep { assertions=assertions==null?List.of():List.copyOf(assertions); }
    }
    /** IR grouping is independent of the execution framework. Calls retain their stable IDs. */
    public record CallGroup(String id, String mode, List<String> callIds, List<GroupAssertion> assertions) {
        public CallGroup(String id,String mode,List<String> callIds) {this(id,mode,callIds,List.of());}
        public CallGroup { assertions=assertions==null?List.of():List.copyOf(assertions); }
    }
    public record CleanupCall(FlowNode call,List<Assertion> assertions,String resourceKey,List<String> when,boolean required,long timeoutMillis) {}
    public record CleanupStep(Step call,String resourceKey,List<String> when,boolean required,long timeoutMillis) {}

    public record Flow(
            String flowId,
            int revision,
            String name,
            Map<String, Variable> initialContext,
            List<FlowNode> nodes,
            List<Dependency> dependencies,
            List<StateModel> stateModels,
            List<TestPoint> testPoints,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<FlowStep> steps,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<CleanupCall> cleanup) {
        public Flow(String flowId,int revision,String name,Map<String,Variable> initialContext,List<FlowNode> nodes,
                    List<Dependency> dependencies,List<StateModel> stateModels,List<TestPoint> testPoints,List<FlowStep> steps) {
            this(flowId,revision,name,initialContext,nodes,dependencies,stateModels,testPoints,steps,null);
        }
        public Flow(String flowId, int revision, String name, Map<String,Variable> initialContext,
                    List<FlowNode> nodes, List<Dependency> dependencies, List<StateModel> stateModels, List<TestPoint> testPoints) {
            this(flowId,revision,name,initialContext,nodes,dependencies,stateModels,testPoints,null);
        }
        public Flow {
            if (steps != null) {
                IrValidator.require(dependencies == null || dependencies.isEmpty(), "Ordered flows do not accept dependency edges");
                IrValidator.require(steps.stream().allMatch(s->s!=null && s.calls()!=null && s.calls().stream().allMatch(java.util.Objects::nonNull)), "Ordered steps require call lists");
                var flattened = steps.stream().flatMap(s -> s.calls().stream()).toList();
                IrValidator.require(nodes == null || nodes.equals(flattened), "Flow calls disagree with ordered steps");
                nodes = flattened;
                dependencies = List.of();
            }
        }
        public Flow withCalls(List<FlowNode> calls, List<TestPoint> points) {
            var byId = new java.util.HashMap<String,FlowNode>();
            calls.forEach(c -> byId.put(c.id(), c));
            var groups = steps == null ? null : steps.stream().map(s -> new FlowStep(s.id(),s.label(),s.mode(),
                    s.calls().stream().map(c -> java.util.Objects.requireNonNull(byId.get(c.id()), "Missing grouped call")).toList(),s.assertions())).toList();
            return new Flow(flowId,revision,name,initialContext,calls,dependencies,stateModels,points,groups,cleanup);
        }
    }

    public record FlowRevision(
            Flow definition, String hash, String status, String confirmedBy, String confirmedAt) {}

    public record Step(
            String id,
            String operationRef,
            Map<String, Binding> inputs,
            List<Output> outputs,
            List<StateConstraint> states,
            List<Assertion> assertions,
            long waitMillis,
            io.yuan.octopus.preparation.PreparationPlan preparation,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Poll poll) {
        public Step(String id,String operationRef,Map<String,Binding> inputs,List<Output> outputs,List<StateConstraint> states,List<Assertion> assertions,long waitMillis,
                    io.yuan.octopus.preparation.PreparationPlan preparation) {
            this(id,operationRef,inputs,outputs,states,assertions,waitMillis,preparation,null);
        }
        public Step(String id,String operationRef,Map<String,Binding> inputs,List<Output> outputs,List<StateConstraint> states,List<Assertion> assertions,long waitMillis) {
            this(id,operationRef,inputs,outputs,states,assertions,waitMillis,null);
        }
    }

    public record TestIR(
            String irVersion,
            String scenarioId,
            String flowId,
            int flowRevision,
            String flowHash,
            String testPointId,
            String oracleHash,
            Map<String, Variable> initialContext,
            List<Operation> operations,
            List<StateModel> stateModels,
            List<Step> steps,
            List<Dependency> dependencies,
            List<String> requiresCapabilities,
            String intentionallyViolatedRule,
            List<String> changeEvidenceRefs,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<CallGroup> executionSteps,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<CleanupStep> cleanup) {
        public TestIR(String irVersion,String scenarioId,String flowId,int flowRevision,String flowHash,String testPointId,String oracleHash,
                      Map<String,Variable> initialContext,List<Operation> operations,List<StateModel> stateModels,List<Step> steps,List<Dependency> dependencies,
                      List<String> requiresCapabilities,String intentionallyViolatedRule,List<String> changeEvidenceRefs,List<CallGroup> executionSteps) {
            this(irVersion,scenarioId,flowId,flowRevision,flowHash,testPointId,oracleHash,initialContext,operations,stateModels,steps,dependencies,
                    requiresCapabilities,intentionallyViolatedRule,changeEvidenceRefs,executionSteps,null);
        }
        public TestIR(String irVersion, String scenarioId, String flowId, int flowRevision, String flowHash,
                      String testPointId, String oracleHash, Map<String,Variable> initialContext, List<Operation> operations,
                      List<StateModel> stateModels, List<Step> steps, List<Dependency> dependencies,
                      List<String> requiresCapabilities, String intentionallyViolatedRule, List<String> changeEvidenceRefs) {
            this(irVersion,scenarioId,flowId,flowRevision,flowHash,testPointId,oracleHash,initialContext,operations,
                    stateModels,steps,dependencies,requiresCapabilities,intentionallyViolatedRule,changeEvidenceRefs,null);
        }
    }

    public record Target(String baseUrl) {}
    public record EnvironmentReference(String key, String repositoryKey, String modulePath) {}
    public record CallEnvironment(Map<String,Object> inputs, Map<String,String> origins, String snapshotHash) {}

    public record ExecutionProfile(
            Map<String, Target> services,
            Map<String, Object> initialContext,
            Map<String, String> secretEnvironmentNames,
            String environment,
            Map<String,CallEnvironment> callEnvironments) {
        public ExecutionProfile(Map<String,Target> services,Map<String,Object> initialContext,Map<String,String> secretEnvironmentNames,String environment) {
            this(services,initialContext,secretEnvironmentNames,environment,null);
        }
        public ExecutionProfile(Map<String, Target> services, Map<String,Object> initialContext, Map<String,String> secretEnvironmentNames) {
            this(services, initialContext, secretEnvironmentNames, null);
        }
    }

    public record Capability(
            String id,
            String version,
            String compiler,
            String runtime,
            List<String> supportedFeatures) {}

    public record Compilation(
            String jmx,
            String irHash,
            Map<String, String> sourceMap,
            List<Capability> capabilities) {}
}
