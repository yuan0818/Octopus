package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.hash;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ScenarioPlanner {
    private final BusinessKnowledgeStore store;
    private final IrValidator validator;

    public ScenarioPlanner(BusinessKnowledgeStore store, IrValidator validator) {
        this.store = store;
        this.validator = validator;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PollAuthorizationService pollAuthorizations;

    public void validateFlow(Flow flow) {
        require(
                flow != null && flow.name() != null && !flow.name().isBlank(),
                "Flow name required");
        // FR-POLL-06: confirming any flow (canvas or DSL) requires every poll to be on an authorized API.
        if (pollAuthorizations != null && flow.nodes() != null) pollAuthorizations.verify(flow, store::operation);
        IrValidator.identifier(flow.flowId(), "flowId");
        require(
                flow.revision() >= 0
                        && flow.nodes() != null
                        && flow.testPoints() != null
                        && !flow.testPoints().isEmpty()
                        && flow.dependencies() != null
                        && flow.stateModels() != null
                        && flow.initialContext() != null,
                "Incomplete flow");
        Set<String> pointIds = new HashSet<>();
        for (var point : flow.testPoints()) {
            IrValidator.identifier(point.id(), "test point");
            require(pointIds.add(point.id()), "Duplicate test point");
            plan(
                    new FlowRevision(flow, hash(flow), "DRAFT", null, null),
                    point.id(),
                    List.of(),
                    false);
        }
    }

    /** Drafts may contain missing bindings/oracles; executable confirmation remains strict. */
    public void validateDraft(Flow flow) {
        require(flow != null && flow.name() != null && !flow.name().isBlank(), "Flow name required");
        IrValidator.identifier(flow.flowId(), "flowId");
        require(flow.initialContext() != null && flow.nodes() != null && flow.stateModels() != null
                && flow.dependencies() != null && flow.testPoints() != null && flow.revision() >= 0, "Incomplete draft");
        require(!flow.nodes().isEmpty() && flow.nodes().size() <= 300, "Draft requires 1..300 calls");
        Set<String> ids = new HashSet<>();
        for (var call : flow.nodes()) {
            IrValidator.identifier(call.id(), "call id");
            require(ids.add(call.id()) && call.inputs() != null && call.outputs() != null && call.states() != null, "Invalid draft call");
            store.operation(call.operationRef());
        }
        if (flow.steps() != null) {
            OrderedFlows.prefix(flow, new TestPoint("draft","Draft",List.of(),Map.of(),Map.of(),null));
            for (var point : flow.testPoints()) OrderedFlows.prefix(flow, point);
        }
    }

    public record Migration(Flow flow, String baseHash, Map<String,String> idMap, List<String> executionOrder, List<String> warnings) {}
    public Migration migrate(Flow legacy, String pointId) {
        require(legacy.steps() == null, "Flow already uses ordered steps");
        var ir = plan(new FlowRevision(legacy, hash(legacy), "DRAFT", null, null), pointId, List.of(), false);
        var order = validator.validate(ir).stream().map(Step::id).toList();
        var byId = new HashMap<String,FlowNode>(); legacy.nodes().forEach(n -> byId.put(n.id(),n));
        var steps = order.stream().map(id -> new FlowStep(id,byId.get(id).label(),"SERIAL",List.of(byId.get(id)))).toList();
        var flow = new Flow(legacy.flowId(),legacy.revision(),legacy.name(),legacy.initialContext(),null,List.of(),legacy.stateModels(),legacy.testPoints(),steps);
        List<String> warnings = new ArrayList<>(List.of("迁移结果是待审阅草稿；保存为新修订后重新确认，历史运行保留原版本。"));
        if (!order.equals(legacy.nodes().stream().map(FlowNode::id).toList())) warnings.add("显示顺序已按所选旧用例的真实执行顺序调整，请审阅。");
        Map<String,String> mapping = new LinkedHashMap<>(); order.forEach(id -> mapping.put(id,id+"/"+id));
        try { validateFlow(flow); } catch (IllegalArgumentException e) { warnings.add("其他测试点或输入仍需修复："+e.getMessage()); }
        return new Migration(flow,hash(legacy),mapping,order,warnings);
    }

    public TestIR generate(String flowId, int revision, String pointId, List<String> evidence) {
        store.validateEvidence(flowId, revision, pointId, evidence);
        return plan(store.flow(flowId, revision), pointId, evidence, true);
    }

    /**
     * 用例生成（Operator 静态校验与探测）：对已确认流程修订和一个尚未加入流程的候选测试点生成 IR。
     * 校验规则与确认后生成场景完全相同，包括“仅凭状态码不足”和负向用例的无副作用断言要求。
     */
    public TestIR planCandidate(FlowRevision confirmed, TestPoint candidate) {
        require(candidate != null, "Candidate test point required");
        IrValidator.identifier(candidate.id(), "test point");
        require(confirmed.definition().testPoints().stream().noneMatch(p -> p.id().equals(candidate.id())), "Candidate id collides with a confirmed test point");
        return plan(confirmed, candidate, List.of(), true);
    }

    private TestIR plan(
            FlowRevision revision, String pointId, List<String> evidence, boolean confirmed) {
        require(
                !confirmed || "CONFIRMED".equals(revision.status()),
                "Only confirmed flow revisions can generate scenarios");
        TestPoint point =
                revision.definition().testPoints().stream()
                        .filter(p -> p.id().equals(pointId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown test point"));
        return plan(revision, point, evidence, confirmed);
    }

    private TestIR plan(
            FlowRevision revision, TestPoint point, List<String> evidence, boolean confirmed) {
        require(
                !confirmed || "CONFIRMED".equals(revision.status()),
                "Only confirmed flow revisions can generate scenarios");
        Flow flow = revision.definition();
        require(
                point.covers() != null
                        && !point.covers().isEmpty()
                        && point.assertions() != null
                        && point.inputOverrides() != null,
                "Incomplete test point");
        var groups = OrderedFlows.prefix(flow, point);
        var included = groups == null ? flow.nodes() : groups.stream().flatMap(s -> s.calls().stream()).toList();
        if(groups!=null) require(included.stream().anyMatch(c->!c.states().isEmpty()
                || point.assertions().getOrDefault(c.id(),List.of()).stream().anyMatch(a->!"STATUS_EQUALS".equals(a.kind())))
                || groups.stream().anyMatch(g->!g.assertions().isEmpty()),
                "Ordered test cases require a business field, state, datastore, or step comparison oracle; HTTP status alone is insufficient");
        Set<String> ids = new HashSet<>();
        included.forEach(n -> ids.add(n.id()));
        var suppressed=point.suppressedOutputCalls()==null?List.<String>of():point.suppressedOutputCalls();
        require(ids.containsAll(suppressed),"Suppressed output call is outside the case scope");
        for(String id:suppressed) {
            require(groups!=null && point.endAtStepRef()!=null,"Negative output suppression requires a static step prefix");
            var last=groups.getLast();
            require(last.calls().stream().anyMatch(c->c.id().equals(id)) &&
                    ("PARALLEL".equals(last.mode()) || last.calls().getLast().id().equals(id)),"Split the step before suppressing outputs consumed later in a serial group");
            require(point.assertions().getOrDefault(id,List.of()).stream().anyMatch(a->"STATUS_EQUALS".equals(a.kind()) && a.expected() instanceof Number n && n.intValue()>=400 && n.intValue()<=599),
                    "Suppressed outputs require an explicit rejection oracle");
            require(point.assertions().getOrDefault(id,List.of()).stream().anyMatch(a->!"STATUS_EQUALS".equals(a.kind())),
                    "Negative cases require an explicit business/no-side-effect oracle in addition to a rejection status");
        }
        require(
                ids.containsAll(point.covers())
                        && ids.containsAll(point.assertions().keySet())
                        && ids.containsAll(point.inputOverrides().keySet()),
                "Test point references missing nodes");
        Map<String, Operation> operations = new TreeMap<>();
        List<Step> steps = new ArrayList<>();
        Set<String> capabilities = new TreeSet<>(List.of("http", "json"));
        if (groups != null) capabilities.add("flow.ordered");
        for (var node : included) {
            point.assertions().getOrDefault(node.id(), List.of()).stream().filter(a -> DatastoreChecks.KIND.equals(a.kind()))
                    .forEach(a -> capabilities.add(DatastoreChecks.capability(DatastoreChecks.plan(a))));
            if (point.assertions().getOrDefault(node.id(), List.of()).stream().anyMatch(a -> ResponseSchemaChecks.KIND.equals(a.kind())))
                capabilities.add(ResponseSchemaChecks.CAPABILITY);
            Operation operation = store.operation(node.operationRef());
            if(node.preparation()!=null) capabilities.add("request.prepare");
            if(ExtensionUsage.uses(node.preparation())) capabilities.add(ExtensionUsage.CAPABILITY);
            if(node.poll()!=null) capabilities.add(PollChecks.CAPABILITY);
            require(
                    !confirmed
                            || operation.requestSchema() != null
                                    && operation.responseSchema() != null,
                    "API contract incomplete: "
                            + operation.ref()
                            + "; import OpenAPI or normalize the contract");
            operations.put(operation.ref(), operation);
            capabilities.addAll(operation.requiresCapabilities());
            if (operation.securityProfile() != null && !operation.securityProfile().isBlank())
                capabilities.add("crypto." + operation.securityProfile());
            Map<String, Binding> inputs = new TreeMap<>(node.inputs());
            inputs.putAll(point.inputOverrides().getOrDefault(node.id(), Map.of()));
            steps.add(
                    new Step(
                            node.id(),
                            node.operationRef(),
                            inputs,
                            suppressed.contains(node.id())?List.of():node.outputs(),
                            node.states(),
                            point.assertions().getOrDefault(node.id(), List.of()),
                            node.waitMillis(), node.preparation(), node.poll()));
        }
        List<CleanupStep> cleanup=null;
        if(flow.cleanup()!=null && !flow.cleanup().isEmpty()) {
            require(groups!=null,"Cleanup requires an ordered flow");
            cleanup=new ArrayList<>();
            for(var item:flow.cleanup()) {
                require(flow.nodes().stream().flatMap(n->n.outputs().stream()).anyMatch(o->o.key().equals(item.resourceKey())),"Cleanup resource key is not declared");
                if(steps.stream().flatMap(n->n.outputs().stream()).noneMatch(o->o.key().equals(item.resourceKey()))) continue;
                var call=item.call();require(call.poll()==null,"清理步骤不支持轮询");var operation=store.operation(call.operationRef());operations.put(operation.ref(),operation);
                capabilities.addAll(operation.requiresCapabilities());
                if(call.preparation()!=null) capabilities.add("request.prepare");
                if(ExtensionUsage.uses(call.preparation())) capabilities.add(ExtensionUsage.CAPABILITY);
                var step=new Step(call.id(),call.operationRef(),call.inputs(),call.outputs(),call.states(),item.assertions(),call.waitMillis(),call.preparation());
                for(var assertion:DatastoreChecks.of(step)) capabilities.add(DatastoreChecks.capability(DatastoreChecks.plan(assertion)));
                if(step.assertions().stream().anyMatch(a -> ResponseSchemaChecks.KIND.equals(a.kind()))) capabilities.add(ResponseSchemaChecks.CAPABILITY);
                cleanup.add(new CleanupStep(step,item.resourceKey(),item.when(),item.required(),item.timeoutMillis()));
            }
        }
        TestIR ir =
                new TestIR(
                        groups == null ? "0.1" : "0.2",
                        flow.flowId() + "." + point.id(),
                        flow.flowId(),
                        Math.max(1, flow.revision()),
                        revision.hash(),
                        point.id(),
                        hash(point),
                        flow.initialContext(),
                        new ArrayList<>(operations.values()),
                        flow.stateModels(),
                        steps,
                        flow.dependencies(),
                        new ArrayList<>(capabilities),
                        point.intentionallyViolatedRule(),
                        evidence == null
                                ? List.of()
                                : evidence.stream().distinct().sorted().toList(),
                        groups == null ? null : groups.stream().map(g -> new CallGroup(g.id(), g.mode(), g.calls().stream().map(FlowNode::id).toList(),g.assertions())).toList(),cleanup);
        validator.validate(ir);
        return ir;
    }
}
