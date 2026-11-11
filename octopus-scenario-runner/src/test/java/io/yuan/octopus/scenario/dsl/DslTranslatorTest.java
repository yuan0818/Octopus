package io.yuan.octopus.scenario.dsl;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;

import io.yuan.octopus.scenario.testing.PollAuthorizationRules;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * FR-LANG / FR-DERIVE-01/02 / FR-CONV-02 / FR-POLL / FR-LIN-01: deterministic translation of declared items into
 * an ordered, strictly linear flow, with provenance, without any LLM.
 */
class DslTranslatorTest {
    private static Map<String, Object> object(Map<String, Object> props) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", "object");
        m.put("properties", props);
        return m;
    }

    private static Map<String, Object> type(String t) {
        return Map.of("type", t);
    }

    private static Operation op(String method, String path, Map<String, Port> inputs, Map<String, Object> success) {
        var impl = new Implementation(1, "main", "a".repeat(40), "com.shop.Api." + method + path.hashCode() + "()");
        var response = new LinkedHashMap<String, Object>();
        if (success != null) response.put("200", success);
        return new Operation("op." + method + path.replaceAll("[^a-z]", ""), "r1", "shop", "order", method, path, inputs, Map.of("type", "object"),
                response, List.of(impl), new Source("CODE_CONTRACT", "x", "d"), List.of("http", "json"), null);
    }

    static final Operation CREATE = op("POST", "/orders", Map.of(
            "body.amount", new Port("body", "amount", "integer", true, null),
            "body.note", new Port("body", "note", "string", false, null)),
            object(Map.of("id", type("integer"), "state", type("string"))));
    static final Operation PAY = op("POST", "/orders/{orderId}/pay", Map.of(
            "path.orderId", new Port("path", "orderId", "integer", true, null)), object(Map.of("paid", type("boolean"))));
    static final Operation REFUND = op("POST", "/refunds", Map.of(
            "body.orderId", new Port("body", "orderId", "integer", true, null),
            "body.reason", new Port("body", "reason", "string", true, null),
            "header.X-Tenant", new Port("header", "X-Tenant", "string", false, null)),
            object(Map.of("id", type("integer"), "state", type("string"), "amount", type("integer"))));
    static final Operation STATUS = op("GET", "/exports/{id}", Map.of("path.id", new Port("path", "id", "integer", true, null)),
            object(Map.of("state", type("string"))));
    static final Operation DELETE = op("DELETE", "/orders/{id}", Map.of("path.id", new Port("path", "id", "integer", true, null)), null);

    private final Map<String, PollAuthorizationRules.Grant> grants = new HashMap<>();

    private DslTranslator translator() {
        var ops = List.of(CREATE, PAY, REFUND, STATUS, DELETE);
        DslTranslator.ApiResolver apis = (method, path, service) -> {
            var matches = ops.stream().filter(o -> (method == null || o.method().equals(method)) && DslTranslator.pathMatches(path, o.path()))
                    .collect(Collectors.toList());
            if (matches.size() != 1) throw new DslTranslator.ResolveException("API_NOT_FOUND", "没有找到 " + path, List.of());
            return new DslTranslator.Resolved(matches.get(0), matches.get(0).path(), List.of());
        };
        DslTranslator.FunctionResolver fns = (name, version) -> {
            if (name.equals("uuid")) return Optional.of(new DslTranslator.FunctionRef("uuid", "1", List.of(), List.of(), "value", "string", List.of("REQUEST_VALUE")));
            if (name.equals("digest")) return Optional.of(new DslTranslator.FunctionRef("digest", "1", List.of("algorithm", "text"), List.of("string", "string"), "value", "string",
                    List.of("REQUEST_VALUE", "EXPECTED_VALUE")));
            return Optional.empty();
        };
        DslTranslator.PollResolver polls = operation -> Optional.ofNullable(grants.get(operation.path()));
        return new DslTranslator(apis, fns, polls);
    }

    private static String dsl(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void explicitStepsBecomeALockedLinearFlowWithoutLlm() {
        var result = translator().translate(dsl(
                "feature: 退款",
                "scenarios:",
                "  - name: 已支付订单可以申请退款",
                "    id: refund-paid",
                "    steps:",
                "      - call: POST /orders",
                "        with: { amount: 100 }",
                "        as: order",
                "      - call: POST /orders/{id}/pay",
                "        with:",
                "          id: ${order.id}",
                "      - call: POST /refunds",
                "        with:",
                "          orderId: ${order.id}",
                "          reason: 质量问题",
                "          X-Tenant: ${env.tenant}",
                "        expect:",
                "          - $.state == \"REFUND_PENDING\"",
                "          - $.id exists",
                "          - conforms",
                "    cleanup:",
                "      - call: DELETE /orders/{id}",
                "        with:",
                "          id: ${order.id}"), "dsl");
        var s = result.scenarios.get(0);
        assertTrue(s.translated(), String.valueOf(s.diagnostics));
        var flow = s.flow;
        assertEquals("dsl.refund-paid", flow.flowId());
        assertEquals(3, flow.steps().size());
        assertTrue(flow.steps().stream().allMatch(st -> st.mode().equals("SERIAL") && st.calls().size() == 1), "strictly linear");
        var create = flow.nodes().get(0);
        assertEquals(new Binding("literal", 100L), create.inputs().get("body.amount"));
        assertEquals(1, create.outputs().size());
        assertEquals("order.id", create.outputs().get(0).key());
        assertEquals("$.id", create.outputs().get(0).path());
        assertEquals("integer", create.outputs().get(0).type());
        // The DSL placeholder {id} maps by position to the code's {orderId}.
        assertEquals(new Binding("context", "order.id"), flow.nodes().get(1).inputs().get("path.orderId"));
        var refund = flow.nodes().get(2);
        assertEquals(new Binding("context", "order.id"), refund.inputs().get("body.orderId"));
        assertEquals(new Binding("literal", "质量问题"), refund.inputs().get("body.reason"));
        assertEquals(new Binding("environment", "tenant"), refund.inputs().get("header.X-Tenant"));
        var checks = flow.testPoints().get(0).assertions().get("c3");
        assertEquals(List.of("STATUS_EQUALS", "JSON_EQUALS", "JSON_EXISTS", "SCHEMA_CONFORMS"), checks.stream().map(Assertion::kind).collect(Collectors.toList()));
        assertEquals(200, ((Number) checks.get(0).expected()).intValue());
        assertTrue(s.provenance.stream().anyMatch(p -> p.nodeId.equals("c3") && p.item.equals("status") && p.source.equals("CONTRACT")));
        assertEquals("order.id", flow.cleanup().get(0).resourceKey());
        assertEquals(List.of("GIVEN", "GIVEN", "WHEN"), s.roles);
    }

    @Test
    void pathTypeMismatchIsReportedNotCoerced() {
        var result = translator().translate(dsl(
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /orders",
                "        with: { amount: 1 }",
                "        as: order",
                "      - call: POST /refunds",
                "        with:",
                "          orderId: ${order.id}",
                "          reason: ${order.id}"), "dsl");
        var s = result.scenarios.get(0);
        assertTrue(s.diagnostics.stream().anyMatch(d -> d.code.equals("TYPE_MISMATCH")), "integer id cannot silently feed a string port");
        assertNull(s.flow);
    }

    @Test
    void sameNameSameTypeDependenciesAreDerivedByRuleAndFlaggedForReview() {
        var s = translator().translate(dsl(
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /orders",
                "        with: { amount: 1 }",
                "        as: order",
                "      - call: POST /refunds",
                "        with: { reason: r }"), "dsl").scenarios.get(0);
        assertTrue(s.translated(), String.valueOf(s.diagnostics));
        assertEquals(new Binding("context", "order.id"), s.flow.nodes().get(1).inputs().get("body.orderId"));
        assertTrue(s.provenance.stream().anyMatch(p -> p.item.equals("body.orderId") && p.source.equals("RULE")));
    }

    @Test
    void missingInputsArePendingNotGuessed() {
        var s = translator().translate(dsl("feature: x", "scenarios:", "  - name: a", "    steps:", "      - POST /refunds"), "dsl").scenarios.get(0);
        assertTrue(s.translated());
        var items = s.pending.stream().map(p -> p.item).collect(Collectors.toSet());
        assertTrue(items.contains("body.orderId"));
        assertTrue(items.contains("body.reason"));
    }

    @Test
    void functionsAndInterpolationBecomeAPreparationPlan() {
        var s = translator().translate(dsl(
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /orders",
                "        with:",
                "          amount: 5",
                "          note: ${fn.digest(\"SHA-256\", \"abc\")}",
                "        headers:",
                "          X-Request-Id: ${fn.uuid()}",
                "          Authorization: Bearer ${secret.token}"), "dsl").scenarios.get(0);
        assertTrue(s.translated(), String.valueOf(s.diagnostics));
        var plan = s.flow.nodes().get(0).preparation();
        assertNotNull(plan);
        var tools = plan.tools().stream().map(c -> c.tool()).collect(Collectors.toList());
        assertTrue(tools.containsAll(List.of("digest", "json.object", "uuid", "string.concat")), tools.toString());
        assertEquals("result", plan.body().source());
        assertEquals("result", plan.headers().get("Authorization").source());
        assertTrue(s.flow.nodes().get(0).inputs().isEmpty(), "the assembled body replaces individual body bindings");
        var concat = plan.tools().stream().filter(c -> c.tool().equals("string.concat")).findFirst().orElseThrow();
        assertEquals("secret", concat.inputs().get("value").source());
    }

    @Test
    void unknownFunctionsAndUnknownParamsAreErrors() {
        var s = translator().translate(dsl(
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /orders",
                "        with:",
                "          amount: 1",
                "          nope: 2",
                "        headers:",
                "          X-A: ${fn.acme.sign(\"x\")}"), "dsl").scenarios.get(0);
        var codes = s.diagnostics.stream().map(d -> d.code).collect(Collectors.toSet());
        assertTrue(codes.contains("PARAM_UNKNOWN"));
        assertTrue(codes.contains("FN_UNKNOWN"));
        assertNull(s.flow);
    }

    @Test
    void pollingRequiresAnExplicitAuthorizationAndStaysWithinIt() {
        String source = dsl(
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: GET /exports/{id}",
                "        with: { id: 7 }",
                "        poll: true",
                "        expect: '$.state == \"DONE\"'");
        var denied = translator().translate(source, "dsl").scenarios.get(0);
        assertTrue(denied.diagnostics.stream().anyMatch(d -> d.code.equals("POLL_UNAUTHORIZED")));
        grants.put("/exports/{id}", new PollAuthorizationRules.Grant("g1", 3, 2_000, 30, 60_000));
        var allowed = translator().translate(source, "dsl").scenarios.get(0);
        assertTrue(allowed.translated(), String.valueOf(allowed.diagnostics));
        var poll = allowed.flow.nodes().get(0).poll();
        assertEquals(2_000, poll.intervalMillis());
        assertEquals(58_000, poll.timeoutMillis());
        assertEquals("g1@3", poll.authorizationRef());
        var tooFast = translator().translate(source.replace("poll: true", "poll: { every: 1s, within: 10s }"), "dsl").scenarios.get(0);
        assertTrue(tooFast.diagnostics.stream().anyMatch(d -> d.code.equals("POLL_EXCEEDS_AUTHORIZATION")));
    }

    @Test
    void negativeStepEndsThePointAndSuppressesOutputs() {
        var s = translator().translate(dsl(
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /orders",
                "        with: { amount: 1 }",
                "        as: order",
                "      - call: POST /refunds",
                "        with:",
                "          orderId: ${order.id}",
                "          reason: r",
                "        expect: { status: 409, $.code: ORDER_NOT_PAID }"), "dsl").scenarios.get(0);
        assertTrue(s.translated(), String.valueOf(s.diagnostics));
        var point = s.flow.testPoints().get(0);
        assertEquals("s2", point.endAtStepRef());
        assertEquals(List.of("c2"), point.suppressedOutputCalls());
        assertEquals(409, ((Number) point.assertions().get("c2").get(0).expected()).intValue());
    }

    /** FR-RT-01/02: a DSL-origin flow renders back to DSL that translates to an identical flow. */
    @Test
    void flowRendersBackToDslAndTranslatesToTheSameFlow() {
        grants.put("/exports/{id}", new PollAuthorizationRules.Grant("g1", 3, 2_000, 30, 60_000));
        var source = dsl(
                "feature: 退款",
                "scenarios:",
                "  - name: 已支付订单可以申请退款",
                "    id: refund-paid",
                "    inputs:",
                "      tenant: string",
                "    steps:",
                "      - call: POST /orders",
                "        with:",
                "          amount: 100",
                "          note: ${fn.digest(\"SHA-256\", ${input.tenant})}",
                "        headers:",
                "          X-Request-Id: ${fn.uuid()}",
                "          Authorization: Bearer ${secret.token}",
                "          X-Static: fixed",
                "        as: order",
                "      - call: POST /orders/{id}/pay",
                "        name: 支付订单",
                "        with:",
                "          id: ${order.id}",
                "        wait: 2s",
                "      - call: POST /refunds",
                "        with:",
                "          orderId: ${order.id}",
                "          reason: 质量问题",
                "        expect:",
                "          - $.state == \"REFUND_PENDING\"",
                "          - $.amount == 100",
                "          - $.id exists",
                "          - conforms",
                "      - call: GET /exports/{id}",
                "        with: { id: 7 }",
                "        poll: true",
                "        expect: '$.state == \"DONE\"'",
                "    cleanup:",
                "      - call: DELETE /orders/{id}",
                "        with:",
                "          id: ${order.id}");
        var first = translator().translate(source, "dsl").scenarios.get(0);
        assertTrue(first.translated(), String.valueOf(first.diagnostics));
        var ops = new HashMap<String, Operation>();
        first.operations.forEach(o -> ops.put(o.ref(), o));
        var rendered = FlowDslRenderer.render(first.flow, ops::get, ref -> Optional.ofNullable(grants.get(ops.get(ref).path())));
        assertTrue(rendered.ok(), String.valueOf(rendered.diagnostics));
        var second = translator().translate(rendered.text, "dsl").scenarios.get(0);
        assertTrue(second.translated(), rendered.text + "\n" + second.diagnostics);
        assertEquals(io.yuan.octopus.scenario.testing.TestingJson.hash(first.flow), io.yuan.octopus.scenario.testing.TestingJson.hash(second.flow), rendered.text);
        assertTrue(rendered.text.contains("poll: true"));
        assertTrue(rendered.text.contains("Authorization: Bearer ${secret.token}"));
        assertTrue(rendered.text.contains("name: \"支付订单\""));
        // Rendering is canonical: rendering the re-translated flow gives the same text.
        assertEquals(rendered.text, FlowDslRenderer.render(second.flow, ops::get, ref -> Optional.ofNullable(grants.get(ops.get(ref).path()))).text);
    }

    @Test
    void parallelFlowsAreNotRenderable() {
        var node = new FlowNode("c1", "x", CREATE.ref(), Map.of(), List.of(), List.of(), 0, null, null);
        var node2 = new FlowNode("c2", "y", CREATE.ref(), Map.of(), List.of(), List.of(), 0, null, null);
        var flow = new Flow("f", 0, "f", Map.of(), List.of(node, node2), List.of(), List.of(),
                List.of(new TestPoint("tp", "t", List.of("c1", "c2"), Map.of(), Map.of(), null, null, null)),
                List.of(new FlowStep("s1", "s", "PARALLEL", List.of(node, node2))), null);
        var rendered = FlowDslRenderer.render(flow, ref -> CREATE, ref -> Optional.empty());
        assertFalse(rendered.ok());
        assertEquals("RT_PARALLEL", rendered.diagnostics.get(0).code);
    }

    @Test
    void concretePathValuesAreLockedLiterals() {
        var s = translator().translate(dsl("feature: x", "scenarios:", "  - name: a", "    steps:", "      - call: GET /exports/123",
                "        expect: status 200"), "dsl").scenarios.get(0);
        assertTrue(s.translated(), String.valueOf(s.diagnostics));
        assertEquals(new Binding("literal", 123L), s.flow.nodes().get(0).inputs().get("path.id"));
    }
}
