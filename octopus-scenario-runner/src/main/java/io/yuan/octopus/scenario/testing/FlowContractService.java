package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Materializes code contracts as new immutable API revisions, before deciding the model's editable inputs. */
@Service
public class FlowContractService {
    public record Prepared(Flow flow, List<Operation> operations, List<String> warnings) {}
    private final RepositoryCatalogClient catalog;
    private final BusinessKnowledgeStore store;
    private final IrValidator validator;
    public FlowContractService(RepositoryCatalogClient catalog, BusinessKnowledgeStore store, IrValidator validator) {
        this.catalog = catalog; this.store = store; this.validator = validator;
    }
    public Prepared prepare(Flow flow) {
        return prepare(flow, null);
    }
    public Prepared prepare(Flow flow, String nodeId) {
        FlowFillValidator.validateRequest(new FlowSuggestionService.Request(flow, "test", "读取契约", null, Map.of(), Map.of()));
        require(nodeId == null || flow.nodes().stream().anyMatch(n -> n.id().equals(nodeId)), "请选择已有步骤");
        Map<String, Operation> resolved = new LinkedHashMap<>(); List<String> warnings = new ArrayList<>();
        Map<String, Operation> cache = new HashMap<>();
        for (var node : flow.nodes()) {
            var original = store.operation(node.operationRef());
            var operation = original;
            boolean focus = nodeId == null || node.id().equals(nodeId);
            if (focus && cache.containsKey(node.operationRef())) operation = cache.get(node.operationRef());
            else if (focus && Set.of("ANALYZED_CODE", "ENDPOINT").contains(original.source().kind()) && original.implementations().size() == 1) {
                var impl = original.implementations().getFirst();
                var contract = catalog.contract(impl, original.method(), original.path());
                require(contract != null && contract.projectId() == impl.projectId() && impl.branch().equals(contract.branch())
                        && impl.commitId().equals(contract.commitId()) && impl.signature().replaceAll("\\s+", "").equals(contract.signature().replaceAll("\\s+", ""))
                        && original.method().equals(contract.httpMethod()) && original.path().equals(contract.path()), "契约与所选接口版本不一致");
                var inputs = new LinkedHashMap<>(contract.inputs());
                // Existing declared ports and human bindings must remain compatible.
                original.inputs().forEach((key, port) -> {
                    require(!inputs.containsKey(key) || inputs.get(key).equals(port), "原输入端口与代码契约冲突，请先检查接口配置"); inputs.putIfAbsent(key, port);
                });
                var request = original.requestSchema() == null ? contract.requestSchema() : original.requestSchema();
                var response = original.responseSchema() == null ? contract.responseSchema() : original.responseSchema();
                var identity = new LinkedHashMap<String, Object>();
                identity.put("version", 1); identity.put("original", original);
                identity.put("inputs", inputs); identity.put("request", request); identity.put("response", response);
                String digest = hash(identity);
                operation = new Operation(original.operationId(), "contract_" + digest.substring(0, 32), original.system(), original.service(),
                        original.method(), original.path(), inputs, request, response, original.implementations(),
                        new Source("CODE_CONTRACT", impl.signature(), digest), original.requiresCapabilities(), original.securityProfile());
                validator.validateOperation(operation); operation = store.saveOperation(operation);
                contract.warnings().forEach(w -> warnings.add(original.method() + " " + original.path() + "：" + w));
            } else if (focus && (original.requestSchema() == null || original.responseSchema() == null)) {
                warnings.add(original.method() + " " + original.path() + " 缺少契约；需要补充文档或明确唯一的代码实现版本");
            }
            if (focus && operation.requestSchema() != null && operation.requestSchema().get("x-octopus-contract-warnings") instanceof List<?> list)
                list.forEach(w -> { String warning = original.method() + " " + original.path() + "：" + w; if (!warnings.contains(warning)) warnings.add(warning); });
            if (focus) cache.put(node.operationRef(), operation);
            resolved.put(node.id(), operation);
        }
        var nodes = flow.nodes().stream().map(n -> new FlowNode(n.id(), n.label(), resolved.get(n.id()).ref(), n.inputs(), n.outputs(), n.states(), n.waitMillis(), n.preparation(), n.poll())).toList();
        var prepared = flow.withCalls(nodes, flow.testPoints());
        return new Prepared(prepared, resolved.values().stream().distinct().toList(), warnings);
    }
}
