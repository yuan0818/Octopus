package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;

class FlowSuggestionServiceTest {
    RepositoryCatalogClient catalog; ProjectContextService context; BusinessKnowledgeStore store; FlowModelClient llm; FlowSuggestionService service; Operation operation; Flow flow;
    @BeforeEach void setup() {
        catalog = mock(RepositoryCatalogClient.class); context = mock(ProjectContextService.class); store = mock(BusinessKnowledgeStore.class); llm = mock(FlowModelClient.class);
        var module = new RepositoryCatalogClient.Module(52, "orders", "orders", "master", List.of("master"));
        var unrelated = new RepositoryCatalogClient.Module(53, "other", "other", "master", List.of("master"));
        when(catalog.scopes()).thenReturn(List.of(new RepositoryCatalogClient.Scope("repo:1", "Platform", "/repo", List.of(module, unrelated))));
        when(context.modelContext("repo:1", "orders", "test")).thenReturn(Map.of("description", "订单创建后为待支付", "environment", "test"));
        operation = new Operation("orders", "v1", "shop", "orders", "POST", "/orders",
                Map.of("userId", new Port("query", "userId", "string", true, null)), Map.of("type", "object"),
                Map.of("201", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"), "status", Map.of("type", "string")))),
                List.of(new Implementation(52, "master", "a".repeat(40), "Orders.create")), new Source("HUMAN", "test", ""), List.of("http", "json"), null);
        when(store.operation(operation.ref())).thenReturn(operation);
        flow = new Flow("human_flow", 3, "人规划的订单路径", Map.of("test.user", new Variable("string", false)),
                List.of(new FlowNode("create", "创建", operation.ref(), Map.of(), List.of(), List.of(), 100),
                        new FlowNode("query", "查询", operation.ref(), Map.of(), List.of(), List.of(), 0)),
                List.of(new Dependency("create", "query", "ORDERING_CONSTRAINT", "人安排先创建再查询")), List.of(),
                List.of(new TestPoint("normal", "业务真相", List.of("create", "query"),
                        Map.of("create", List.of(new Assertion("status", "STATUS_EQUALS", null, 201))),
                        Map.of("query", Map.of("userId", new Binding("literal", "human-override"))), "human.rule")));
        service = new FlowSuggestionService(catalog, context, store, llm);
        respond(patch());
    }
    FlowSuggestionService.Request request() { return request(flow, Map.of("test.user", "fixture-user"), Map.of("create", List.of("userId"), "query", List.of("userId"))); }
    FlowSuggestionService.Request request(Flow f, Map<String,Object> values, Map<String,List<String>> editable) {
        return new FlowSuggestionService.Request(f, "test", "创建后查询同一个订单，状态为待支付", "", values, editable);
    }
    FlowSuggestionService.NodeFill fill(String id, Binding binding, List<Output> outputs) {
        return new FlowSuggestionService.NodeFill(id, binding == null ? Map.of() : Map.of("userId", binding), outputs);
    }
    FlowSuggestionService.Patch patch() {
        return new FlowSuggestionService.Patch(List.of(fill("create", new Binding("context", "test.user"), List.of(new Output("order.id", "$.body.id", "string", false))),
                fill("query", new Binding("context", "order.id"), List.of())),
                List.of(new FlowSuggestionService.AssertionFill("normal", "query", new Assertion("pending", "JSON_EQUALS", "$.body.status", "PENDING"))), List.of("请确认业务状态取值"), List.of());
    }
    void respond(Object patch) { when(llm.suggest(any(), any(), any(), any(), any())).thenReturn(new FlowModelClient.Response(canonical(patch), "fixture-model")); }
    FlowSuggestionService.Patch nodes(List<FlowSuggestionService.NodeFill> nodes) { return new FlowSuggestionService.Patch(nodes, List.of(), List.of(), List.of()); }
    @Test void fillsOnlyHumanSelectedRevisionsAndPreservesPathAndOracle() {
        var result = service.suggest(request()); var f = result.flow();
        assertEquals(flow.flowId(), f.flowId()); assertEquals(3, f.revision()); assertEquals(flow.name(), f.name());
        assertEquals(flow.dependencies(), f.dependencies()); assertEquals(flow.stateModels(), f.stateModels());
        assertEquals(flow.initialContext(), f.initialContext()); assertEquals(flow.nodes().stream().map(FlowNode::id).toList(), f.nodes().stream().map(FlowNode::id).toList());
        assertEquals(100, f.nodes().getFirst().waitMillis());
        assertEquals("order.id", f.nodes().get(1).inputs().get("userId").value());
        assertEquals(flow.testPoints().getFirst().inputOverrides(), f.testPoints().getFirst().inputOverrides());
        assertEquals("human.rule", f.testPoints().getFirst().intentionallyViolatedRule());
        assertEquals(flow.testPoints().getFirst().assertions().get("create"), f.testPoints().getFirst().assertions().get("create"));
        assertEquals(hash(flow), result.baseHash()); assertEquals(List.of(operation), result.operations());
        verify(store).operation(operation.ref()); verify(store, never()).operations(); verify(store, never()).saveDraft(any()); verify(store, never()).saveOperation(any());
        verify(catalog, never()).endpoints(any(), any()); verify(context).modelContext("repo:1", "orders", "test"); verifyNoMoreInteractions(context);
        var path = ArgumentCaptor.forClass(Map.class); verify(llm).suggest(any(), any(), any(), any(), path.capture());
        assertTrue(canonical(path.getValue()).contains("fixture-user"));
    }
    @Test void rejectsLegacyCatalogPlanningAndEmptyPathsBeforeModelCall() {
        assertThrows(IllegalArgumentException.class, () -> service.suggest(request(null, Map.of(), Map.of())));
        Flow empty = new Flow(flow.flowId(), 3, "", Map.of(), List.of(), List.of(), List.of(), flow.testPoints());
        assertThrows(IllegalArgumentException.class, () -> service.suggest(request(empty, Map.of(), Map.of()))); verifyNoInteractions(llm);
    }
    @Test void additionsDeletionsReorderingAndOperationChangesCannotReachDraft() {
        var valid = patch();
        for (var bad : List.of(List.of(valid.nodes().getFirst()), List.of(valid.nodes().get(1), valid.nodes().getFirst()),
                List.of(valid.nodes().getFirst(), valid.nodes().get(1), fill("invented", null, List.of())))) {
            respond(nodes(bad)); assertThrows(IllegalStateException.class, () -> service.suggest(request()));
        }
        var json = canonical(valid).replace("\"id\":\"create\"", "\"id\":\"create\",\"operationRef\":\"invented@v1\"");
        when(llm.suggest(any(), any(), any(), any(), any())).thenReturn(new FlowModelClient.Response(json, "fixture"));
        assertThrows(IllegalStateException.class, () -> service.suggest(request())); verify(store, never()).saveDraft(any());
    }
    @Test void preservesExistingInputsUnlessHumanExplicitlySelectsReplacement() {
        var old = flow.nodes().getFirst();
        var existing = new FlowNode(old.id(), old.label(), old.operationRef(), Map.of("userId", new Binding("literal", "keep")), old.outputs(), old.states(), 100);
        var f = new Flow(flow.flowId(), 3, flow.name(), flow.initialContext(), List.of(existing, flow.nodes().get(1)), flow.dependencies(), flow.stateModels(), flow.testPoints());
        assertThrows(IllegalStateException.class, () -> service.suggest(request(f, Map.of(), Map.of("query", List.of("userId")))));
        assertEquals("test.user", service.suggest(request(f, Map.of(), request().editableInputs())).flow().nodes().getFirst().inputs().get("userId").value());
    }
    @Test void futureParallelSelfAndUnknownContextBindingsAreRejected() {
        for (String key : List.of("later.id", "missing", "order.id")) {
            respond(nodes(List.of(fill("create", new Binding("context", key), List.of(new Output("order.id", "$.body.id", "string", false))),
                    fill("query", null, List.of(new Output("later.id", "$.body.id", "string", false))))));
            assertThrows(IllegalStateException.class, () -> service.suggest(request()));
        }
        respond(patch()); var parallel = new Flow(flow.flowId(), 3, flow.name(), flow.initialContext(), flow.nodes(), List.of(), flow.stateModels(), flow.testPoints());
        assertThrows(IllegalStateException.class, () -> service.suggest(request(parallel, Map.of(), request().editableInputs())));
    }
    @Test void undeclaredResponsePathsTypesAndDuplicateProducersAreRejected() {
        for (var output : List.of(new Output("order.id", "$.body.invented", "string", false), new Output("order.id", "$.body.id", "integer", false), new Output("test.user", "$.body.id", "string", false))) {
            respond(nodes(List.of(fill("create", null, List.of(output)), fill("query", null, List.of()))));
            assertThrows(IllegalStateException.class, () -> service.suggest(request()));
        }
    }
    @Test void cannotOverrideOrDuplicateExistingStatusAssertions() {
        for (String id : List.of("status", "another_status")) {
            respond(new FlowSuggestionService.Patch(patch().nodes(), List.of(new FlowSuggestionService.AssertionFill("normal", "create", new Assertion(id, "STATUS_EQUALS", null, 201))), List.of(), List.of()));
            assertThrows(IllegalStateException.class, () -> service.suggest(request()));
        }
    }
    @Test void missingSchemaProducesQuestionsWithoutInventingAnOutput() {
        var unknown = new Operation(operation.operationId(), operation.revision(), operation.system(), operation.service(), operation.method(), operation.path(), operation.inputs(), null, null,
                operation.implementations(), operation.source(), operation.requiresCapabilities(), null);
        when(store.operation(operation.ref())).thenReturn(unknown);
        respond(new FlowSuggestionService.Patch(List.of(fill("create", null, List.of()), fill("query", null, List.of())), List.of(), List.of(), List.of("请补充创建订单的响应定义")));
        var result = service.suggest(request()); assertEquals(canonical(flow), canonical(result.flow())); assertFalse(result.warnings().isEmpty()); assertFalse(result.questions().isEmpty());
        respond(patch()); assertThrows(IllegalStateException.class, () -> service.suggest(request()));
    }
    @Test void presetCredentialsAreRejectedAndCredentialLiteralsAreRedacted() {
        var vars = Map.of("secret.value", new Variable("string", true));
        var f = new Flow(flow.flowId(), 3, flow.name(), vars, flow.nodes(), flow.dependencies(), flow.stateModels(), flow.testPoints());
        assertThrows(IllegalArgumentException.class, () -> service.suggest(request(f, Map.of("secret.value", "do-not-send"), request().editableInputs())));
        verifyNoInteractions(llm);
        var safe = FlowFillValidator.modelPath(Map.of("inputs", Map.of("header.Authorization", new Binding("literal", "do-not-send"),
                "body", new Binding("literal", Map.of("password", "also-do-not-send", "region", "test")))));
        assertFalse(canonical(safe).contains("do-not-send")); assertTrue(canonical(safe).contains("region"));
    }
    @Test void unavailableModelNeverCreatesOrConfirmsAFlow() {
        when(llm.suggest(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> service.suggest(request())); verify(store, never()).saveDraft(any());
    }
    @Test void cannotRemoveOrderingPreviouslyEstablishedByHumanDataBindings() {
        var first = flow.nodes().getFirst(); var second = flow.nodes().get(1);
        var f = new Flow(flow.flowId(), 3, flow.name(), flow.initialContext(), List.of(
                new FlowNode(first.id(), first.label(), first.operationRef(), Map.of(), List.of(new Output("old.id", "$.body.id", "string", false)), first.states(), 0),
                new FlowNode(second.id(), second.label(), second.operationRef(), Map.of("userId", new Binding("context", "old.id")), second.outputs(), second.states(), 0)),
                List.of(), flow.stateModels(), List.of(new TestPoint("normal", "normal", List.of("create", "query"), Map.of(), Map.of(), null)));
        respond(nodes(List.of(fill("create", null, List.of()), fill("query", new Binding("literal", "different"), List.of()))));
        assertThrows(IllegalStateException.class, () -> service.suggest(request(f, Map.of(), request().editableInputs())));
    }
    @Test void structuredRequestCannotIntroduceUndeclaredFields() {
        var body = new Operation(operation.operationId(), operation.revision(), operation.system(), operation.service(), operation.method(), operation.path(),
                Map.of("body", new Port("body", "$", "object", true, null)),
                Map.of("type", "object", "required", List.of("userId"), "properties", Map.of("userId", Map.of("type", "string"))),
                operation.responseSchema(), operation.implementations(), operation.source(), operation.requiresCapabilities(), null);
        when(store.operation(operation.ref())).thenReturn(body);
        for (Map<String,Object> value : List.<Map<String,Object>>of(Map.of("unknown", "x"), Map.of("userId", 9), Map.of("userId", "x", "unknown", "y"))) {
            respond(nodes(List.of(new FlowSuggestionService.NodeFill("create", Map.of("body", new Binding("literal", value)), List.of()), fill("query", null, List.of()))));
            assertThrows(IllegalStateException.class, () -> service.suggest(request(flow, Map.of(), Map.of("create", List.of("body")))));
        }
        respond(nodes(List.of(new FlowSuggestionService.NodeFill("create", Map.of("body", new Binding("literal", Map.of("userId", "x"))), List.of()), fill("query", null, List.of()))));
        assertEquals(Map.of("userId", "x"), service.suggest(request(flow, Map.of(), Map.of("create", List.of("body")))).flow().nodes().getFirst().inputs().get("body").value());
    }
}
