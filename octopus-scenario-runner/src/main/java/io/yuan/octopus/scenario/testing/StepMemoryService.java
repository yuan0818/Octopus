package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class StepMemoryService {
    public record Evidence(String id, long projectId, String commitId, String filePath, int lineStart, int lineEnd, String kind) {}
    public record MemoryDraft(String summary, List<String> decisions, List<String> questions, List<String> evidenceIds) {}
    public record Memory(String summary, List<String> decisions, List<String> questions, List<Evidence> evidence,
                         List<String> inputBindings, List<Output> outputs, String operationRef) {}
    public record View(String id, String nodeId, String state, String model, String createdAt, Memory memory) {}
    private final StepMemoryRepository repository;
    public StepMemoryService(StepMemoryRepository repository) { this.repository = repository; }

    static String fingerprint(Flow flow, String nodeId, String environment, Map<String, String> contextHashes) {
        var relevant = new HashSet<>(FlowFillValidator.ancestors(flow).getOrDefault(nodeId, Set.of())); relevant.add(nodeId);
        var nodes = flow.nodes().stream().filter(n -> relevant.contains(n.id())).toList();
        var checks = flow.testPoints().stream().filter(p -> p.covers().stream().anyMatch(relevant::contains)).map(p -> {
            var row = new LinkedHashMap<String, Object>(); row.put("id", p.id()); row.put("title", p.title());
            var assertions = new TreeMap<String, Object>(); p.assertions().forEach((id, value) -> { if (relevant.contains(id)) assertions.put(id, value); });
            var overrides = new TreeMap<String, Object>(); p.inputOverrides().forEach((id, value) -> { if (relevant.contains(id)) overrides.put(id, value); });
            row.put("assertions", assertions); row.put("overrides", overrides);
            if(flow.steps()!=null) {row.put("endAtStepRef",p.endAtStepRef());row.put("suppressedOutputCalls",p.suppressedOutputCalls());}
            return row;
        }).toList();
        var contexts = new TreeMap<String, String>(); relevant.forEach(id -> contexts.put(id, contextHashes.getOrDefault(id, "missing")));
        var identity=new LinkedHashMap<String,Object>(Map.of("version", 1, "flowId", flow.flowId(), "environment", environment, "nodes", nodes, "checks", checks,
                "initialContext", flow.initialContext(), "contexts", contexts, "dependencies", flow.dependencies().stream()
                        .filter(e -> relevant.contains(e.from()) && relevant.contains(e.to())).toList()));
        if(flow.steps()!=null) identity.put("steps",flow.steps().stream().filter(s->s.calls().stream().anyMatch(c->relevant.contains(c.id())))
                .map(s->new CallGroup(s.id(),s.mode(),s.calls().stream().map(FlowNode::id).toList(),s.assertions())).toList());
        return hash(identity);
    }
    public List<View> views(Flow flow, String environment, Map<String, String> contexts) {
        Map<String, View> result = new LinkedHashMap<>();
        var ids = flow.nodes().stream().map(FlowNode::id).toList();
        Map<String, String> fingerprints = new HashMap<>();
        for (var row : repository.findTop500ByFlowIdAndEnvironmentAndStateOrderByCreatedAtDesc(flow.flowId(), environment, "CONFIRMED")) {
            if (!ids.contains(row.nodeId)) continue;
            String state = row.fingerprint.equals(fingerprints.computeIfAbsent(row.nodeId, id -> fingerprint(flow, id, environment, contexts))) ? "CONFIRMED" : "STALE";
            if (!result.containsKey(row.nodeId) || result.get(row.nodeId).state().equals("STALE") && state.equals("CONFIRMED")) result.put(row.nodeId, view(row, state));
        }
        return List.copyOf(result.values());
    }
    public View propose(Flow flow, String nodeId, String environment, Map<String, String> contexts, String model, Memory memory) {
        var row = new StepMemory(); row.id = UUID.randomUUID().toString(); row.flowId = flow.flowId(); row.nodeId = nodeId;
        row.environment = environment; row.state = "PENDING"; row.fingerprint = fingerprint(flow, nodeId, environment, contexts);
        row.confirmationHash = hash(flow); row.model = model; row.memoryJson = canonical(memory); row.createdAt = Instant.now();
        return view(repository.saveAndFlush(row), "PENDING");
    }
    @Transactional
    public View confirm(String id, Flow flow, String environment, Map<String, String> contexts) {
        var row = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("步骤记忆不存在"));
        require(row.flowId.equals(flow.flowId()) && row.environment.equals(environment) && row.confirmationHash.equals(hash(flow)), "草稿与补全结果不一致，请重新补全当前步骤");
        require(row.fingerprint.equals(fingerprint(flow, row.nodeId, environment, contexts)), "背景或步骤已改变，请重新补全当前步骤");
        row.state = "CONFIRMED"; return view(repository.saveAndFlush(row), "CONFIRMED");
    }
    /** Only the server-side chain coordinator can supply these candidate IDs. Public step requests cannot. */
    List<View> working(List<String> ids, Flow flow, String environment, Map<String, String> contexts) {
        return chainRows(ids, flow, environment, contexts).stream().map(row -> view(row, "WORKING")).toList();
    }
    @Transactional
    public List<View> confirmChain(List<String> ids, Flow flow, String environment, Map<String, String> contexts) {
        var rows = chainRows(ids, flow, environment, contexts); // Validate every row before changing any state.
        rows.forEach(row -> row.state = "CONFIRMED");
        repository.saveAllAndFlush(rows);
        return rows.stream().map(row -> view(row, "CONFIRMED")).toList();
    }
    private List<StepMemory> chainRows(List<String> ids, Flow flow, String environment, Map<String, String> contexts) {
        require(ids.size() <= 50 && new HashSet<>(ids).size() == ids.size(), "链路记忆列表无效");
        var rows = new ArrayList<StepMemory>(); var nodes = new HashSet<String>();
        for (String id : ids) {
            var row = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("链路记忆不存在"));
            require(row.flowId.equals(flow.flowId()) && row.environment.equals(environment) && nodes.add(row.nodeId)
                    && flow.nodes().stream().anyMatch(n -> n.id().equals(row.nodeId)) && Set.of("PENDING", "CONFIRMED").contains(row.state), "链路记忆范围不匹配");
            if(!row.fingerprint.equals(fingerprint(flow,row.nodeId,environment,contexts))) throw new FlowFailure(FlowFailure.Code.CONTEXT_STALE,null,"请从步骤 " + row.nodeId + " 重新生成");
            rows.add(row);
        }
        return rows;
    }
    private View view(StepMemory row, String state) { return new View(row.id, row.nodeId, state, row.model, row.createdAt.toString(), read(row.memoryJson, Memory.class)); }
}
