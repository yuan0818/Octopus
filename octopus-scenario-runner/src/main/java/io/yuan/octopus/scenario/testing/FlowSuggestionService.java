package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Humans own the path. The model can only propose fills for a supplied snapshot. */
@Service
public class FlowSuggestionService {
    public record Request(Flow flow, String environment, String goal, String model,
                          Map<String, Object> contextValues, Map<String, List<String>> editableInputs) {}
    public record NodeFill(String id, Map<String, Binding> inputs, List<Output> outputs, io.yuan.octopus.preparation.PreparationPlan preparation) {
        public NodeFill(String id,Map<String,Binding> inputs,List<Output> outputs) { this(id,inputs,outputs,null); }
    }
    public record AssertionFill(String pointId, String nodeId, Assertion assertion) {}
    public record Patch(List<NodeFill> nodes, List<AssertionFill> assertions, List<String> assumptions, List<String> questions) {}
    public record Suggestion(Flow flow, List<Operation> operations, String model, List<String> assumptions,
                             List<String> questions, List<String> warnings, String baseHash) {}
    private final RepositoryCatalogClient catalog;
    private final ProjectContextService contexts;
    private final BusinessKnowledgeStore knowledge;
    private final FlowModelClient llm;
    public FlowSuggestionService(RepositoryCatalogClient catalog, ProjectContextService contexts, BusinessKnowledgeStore knowledge, FlowModelClient llm) {
        this.catalog = catalog; this.contexts = contexts; this.knowledge = knowledge; this.llm = llm;
    }
    public Suggestion suggest(Request request) {
        require(request != null && request.goal() != null && !request.goal().isBlank() && request.goal().length() <= 12000,
                "请描述这些步骤要验证的行为，最多 12000 字");
        ProjectContextService.environment(request.environment());
        FlowFillValidator.validateRequest(request);
        var flow = request.flow();
        Map<String, Operation> selected = new LinkedHashMap<>();
        // Resolve exact revisions selected by the human. Never search or enumerate API candidates.
        for (var node : flow.nodes()) selected.computeIfAbsent(node.operationRef(), knowledge::operation);
        request.editableInputs().forEach((id, ports) -> {
            var node = flow.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
            require(selected.get(node.operationRef()).inputs().keySet().containsAll(ports), "补全范围包含不存在的输入参数");
        });
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> background = new ArrayList<>();
        var scopes = selected.values().stream().anyMatch(o -> !o.implementations().isEmpty()) ? catalog.scopes() : List.<RepositoryCatalogClient.Scope>of();
        Set<String> usedContexts = new HashSet<>();
        for (var operation : selected.values()) {
            if (operation.requestSchema() != null && operation.requestSchema().get("x-octopus-contract-warnings") instanceof List<?> issues)
                issues.forEach(issue -> warnings.add(operation.method() + " " + operation.path() + "：" + issue));
            if (operation.requestSchema() == null || operation.responseSchema() == null)
                warnings.add(operation.method() + " " + operation.path() + " 缺少完整契约；无法确定的字段需要补充后再生成");
            for (var impl : operation.implementations()) {
                boolean found = false;
                for (var scope : scopes) for (var module : scope.modules()) if (module.projectId() == impl.projectId()) {
                    found = true;
                    if (usedContexts.add(scope.key() + "\n" + module.modulePath())) {
                        var row = new LinkedHashMap<>(contexts.modelContext(scope.key(), module.modulePath(), request.environment()));
                        row.put("repositoryKey", scope.key()); row.put("project", scope.name()); row.put("projectId", module.projectId());
                        background.add(row);
                    }
                }
                if (!found) warnings.add("API " + operation.path() + " 的项目背景无法定位，仅使用固定版本契约");
            }
        }
        var facts = selected.values().stream().map(o -> {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("ref", o.ref()); row.put("system", o.system()); row.put("service", o.service());
            row.put("method", o.method()); row.put("path", o.path()); row.put("inputs", o.inputs());
            row.put("requestSchema", o.requestSchema()); row.put("responseSchema", o.responseSchema()); row.put("implementations", o.implementations());
            return row;
        }).toList();
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("name", flow.name()); path.put("initialContext", flow.initialContext()); path.put("contextValues", request.contextValues());
        path.put("dependencies", flow.dependencies());
        if(flow.steps()!=null) path.put("steps",flow.steps().stream().map(g -> Map.of("id",g.id(),"mode",g.mode(),"callIds",g.calls().stream().map(FlowNode::id).toList())).toList()); path.put("editableInputs", request.editableInputs());
        path.put("nodes", flow.nodes().stream().map(n -> Map.of("id", n.id(), "label", n.label(), "operationRef", n.operationRef(),
                "inputs", n.inputs(), "outputs", n.outputs())).toList());
        path.put("testPoints", flow.testPoints());
        var response = llm.suggest(request.model(), request.goal(), background, facts, FlowFillValidator.modelPath(path));
        Patch patch;
        Flow filled;
        try {
            require(response.content() != null && response.content().length() <= 200000, "模型输出超出限制");
            String content = response.content().strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            patch = read(content, Patch.class);
            filled = FlowFillValidator.apply(request, patch, selected);
        } catch (RuntimeException e) {
            throw new IllegalStateException("模型补全包含越界修改、无效字段或数据来源；原流程未改动，请补充契约或调整说明后重试");
        }
        return new Suggestion(filled, List.copyOf(selected.values()), response.model(), patch.assumptions(), patch.questions(), warnings, hash(flow));
    }
}
