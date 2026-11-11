package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.scenario.testing.*;
import java.util.*;

/** 合成 fixture：登录 → 创建订单。不代表真实项目契约。 */
final class GenerationFixtures {
    private GenerationFixtures() {}

    static Operation login() {
        return new Operation("auth.login", "v1", "shop", "api", "POST", "/login",
                Map.of("body", new Port("body", "$", "object", true, null)),
                Map.of("type", "object"),
                Map.of("200", Map.of("type", "object", "properties", Map.of("token", Map.of("type", "string")))),
                List.of(), new Source("MANUAL", "fixture", "fixture"), List.of("http", "json"), null);
    }

    static Operation create() {
        return new Operation("order.create", "v1", "shop", "api", "POST", "/orders",
                Map.of("quantity", new Port("query", "quantity", "integer", true, null),
                        "token", new Port("header", "Authorization", "string", true, "Bearer ")),
                Map.of("type", "object", "x-octopus-parameters", Map.of("quantity", Map.of("type", "integer", "minimum", 1, "maximum", 100))),
                Map.of("201", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"),
                                "status", Map.of("type", "string", "enum", List.of("CREATED")), "note", Map.of("type", "string"))),
                        "422", Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string", "enum", List.of("QUANTITY_INVALID")),
                                "message", Map.of("type", "string")))),
                List.of(), new Source("MANUAL", "fixture", "fixture"), List.of("http", "json"), null);
    }

    static Operation cancel() {
        return new Operation("order.cancel", "v1", "shop", "api", "DELETE", "/orders/cancel",
                Map.of("id", new Port("query", "id", "string", true, null)), Map.of("type", "object"),
                Map.of("200", Map.of("type", "object")), List.of(), new Source("MANUAL", "fixture", "fixture"), List.of("http", "json"), null);
    }

    static TestPoint happy() {
        return new TestPoint("happy", "正常下单", List.of("create"),
                Map.of("login", List.of(new Assertion("status", "STATUS_EQUALS", null, 200)),
                        "create", List.of(new Assertion("status", "STATUS_EQUALS", null, 201), new Assertion("created", "JSON_EQUALS", "$.status", "CREATED"))),
                Map.of(), null);
    }

    static Flow flow(boolean cleanup) {
        var login = new FlowNode("login", "登录", login().ref(), Map.of("body", new Binding("context", "credentials")),
                List.of(new Output("auth.token", "$.token", "string", true)), List.of(), 0);
        var create = new FlowNode("create", "创建订单", create().ref(),
                Map.of("quantity", new Binding("literal", 2), "token", new Binding("context", "auth.token")),
                List.of(new Output("order.id", "$.id", "string", false)), List.of(), 0);
        List<CleanupCall> cleanups = cleanup ? List.of(new CleanupCall(new FlowNode("release", "取消订单", cancel().ref(),
                Map.of("id", new Binding("context", "order.id")), List.of(), List.of(), 0), List.of(new Assertion("status", "STATUS_EQUALS", null, 200)),
                "order.id", List.of("PASS", "FAIL", "INCONCLUSIVE"), true, 3000)) : null;
        return new Flow("orders", 1, "下单", Map.of("credentials", new Variable("object", true)), null, List.of(), List.of(), List.of(happy()),
                List.of(new FlowStep("s1", "登录", "SERIAL", List.of(login)), new FlowStep("s2", "下单", "SERIAL", List.of(create))), cleanups);
    }

    static FlowRevision confirmed(Flow flow) {
        return new FlowRevision(flow, TestingJson.hash(flow), "CONFIRMED", "reviewer", "now");
    }

    static Map<String, Operation> operations() {
        Map<String, Operation> ops = new LinkedHashMap<>();
        for (var op : List.of(login(), create(), cancel())) ops.put(op.ref(), op);
        return ops;
    }

    static BusinessKnowledgeStore store(Flow flow) {
        var store = org.mockito.Mockito.mock(BusinessKnowledgeStore.class);
        org.mockito.Mockito.when(store.flow(flow.flowId(), flow.revision())).thenReturn(confirmed(flow));
        operations().forEach((ref, op) -> org.mockito.Mockito.when(store.operation(ref)).thenReturn(op));
        return store;
    }

    static ScenarioPlanner planner(BusinessKnowledgeStore store) {
        return new ScenarioPlanner(store, new IrValidator(new CapabilityRegistry()));
    }

    static TaskRequest request(List<Strategy> strategies, ProbeMode mode, Rejection rejection) {
        return new TaskRequest("request_0123456789abcdef", "orders", 1, "happy", strategies, List.of(), 40, null, "fixture-model", rejection,
                new ProbeConfig(mode, mode == ProbeMode.ON ? "gen-sandbox" : null, Map.of("credentials", Map.of("user", "u", "password", "canary-password-77")), null, null));
    }

    /** 模型来源的负向意图：quantity=0 → 422 + code。 */
    static String modelPlan() {
        return """
                {"intents":[{"intentId":null,"parentIntentId":null,"title":"数量为 0 被拒绝","category":"NEGATIVE","callId":"create",
                  "overrides":{"quantity":0},"data":null,"slots":null,"rejectionStatus":422,
                  "assertions":[{"kind":"JSON_EQUALS","path":"$.code","expected":"QUANTITY_INVALID"}],
                  "intentionallyViolatedRule":null,"rationale":"数量下界","evidence":["minimum=1"],"preconditionNotes":null}],
                 "drops":[],"changes":[],"questions":[]}""";
    }
}
