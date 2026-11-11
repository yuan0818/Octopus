package io.yuan.octopus.scenario.testing;

import org.junit.jupiter.api.Test;

import java.util.*;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2.3：LLM 只提供目标与取值，结构由平台按负向规则构造；不合规的草稿被标记 BLOCKED。 */
class NegativeCaseSuggestionServiceTest {

    private static final Operation CREATE = op("create", Map.of("name", new Port("body", "name", "string", true, null)),
            Map.of("200", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string")))));
    private static final Operation PAY = op("pay", Map.of("amount", new Port("body", "amount", "integer", true, null),
                    "token", new Port("header", "Authorization", "string", true, null)),
            Map.of("200", Map.of("type", "object"),
                    "400", Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string", "enum", List.of("AMOUNT_INVALID", "AMOUNT_TOO_LARGE")),
                            "message", Map.of("type", "string")))));

    private static Operation op(String id, Map<String, Port> inputs, Map<String, Object> responses) {
        return new Operation(id, "v1", "shop", "order", "POST", "/" + id, inputs, Map.of(), responses, List.of(),
                new Source("openapi", "x", "d"), List.of(), null);
    }

    private static final FlowNode CREATE_NODE = new FlowNode("create", "创建订单", "create@v1", Map.of("name", new Binding("literal", "a")),
            List.of(new Output("order.id", "$.id", "string", false)), List.of(), 0);
    private static final FlowNode PAY_NODE = new FlowNode("pay", "支付", "pay@v1",
            Map.of("amount", new Binding("literal", 10), "token", new Binding("secret", "pay.token")), List.of(), List.of(), 0);
    private static final TestPoint BASE = new TestPoint("normal", "正常支付", List.of("create", "pay"),
            Map.of("create", List.of(new Assertion("st", "STATUS_EQUALS", null, 200)),
                    "pay", List.of(new Assertion("st", "STATUS_EQUALS", null, 200), new Assertion("ok", "JSON_EXISTS", "$.id", true))),
            Map.of(), null);
    private static final Flow FLOW = new Flow("f", 1, "下单支付", Map.of(), null, List.of(), List.of(), List.of(BASE),
            List.of(new FlowStep("s1", "创建", "SERIAL", List.of(CREATE_NODE)), new FlowStep("s2", "支付", "SERIAL", List.of(PAY_NODE))));
    private static final Map<String, Operation> OPS = Map.of("create@v1", CREATE, "pay@v1", PAY);

    private static CaseCandidateService.Candidate build(NegativeCaseSuggestionService.ModelCandidate candidate) {
        return NegativeCaseSuggestionService.build(FLOW, "hash", BASE, OrderedFlows.prefix(FLOW, BASE), OPS, candidate, "m1");
    }

    @Test
    void validCandidateBecomesNegativeTestPointUnderPlatformRules() {
        var c = build(new NegativeCaseSuggestionService.ModelCandidate("负数金额被拒绝", "pay", Map.of("amount", -1), 400,
                List.of(new NegativeCaseSuggestionService.ModelAssertion("JSON_EQUALS", "$.body.code", "AMOUNT_INVALID")), null, "r", List.of()));
        assertEquals("NEEDS_REVIEW", c.status(), String.valueOf(c.issues()));
        var point = c.point();
        assertEquals("s2", point.endAtStepRef());
        assertEquals(List.of("pay"), point.covers());
        assertEquals(List.of("pay"), point.suppressedOutputCalls());
        assertEquals(new Binding("literal", -1), point.inputOverrides().get("pay").get("amount"));
        var payChecks = point.assertions().get("pay");
        assertEquals(400, payChecks.get(0).expected());
        assertEquals("$.code", payChecks.get(1).path(), "LLM $.body paths are normalized to the editor's root form");
        assertTrue(point.assertions().containsKey("create"), "prerequisite oracles are kept");
        assertTrue(point.generatedFrom().sourceRule().startsWith("LLM:"));
    }

    @Test
    void valueWithoutContractEnumIsKeptAsMissingExpectationNotInvented() {
        // PLAN-002：契约只声明了字段，没有声明取值；模型给出的取值只作为建议，不进入断言
        var c = build(new NegativeCaseSuggestionService.ModelCandidate("负数金额被拒绝", "pay", Map.of("amount", -1), 400,
                List.of(new NegativeCaseSuggestionService.ModelAssertion("JSON_EQUALS", "$.message", "金额不能为负")), null, "r", List.of()));
        assertEquals("BLOCKED", c.status());
        assertTrue(c.issues().stream().anyMatch(i -> i.contains("$.message") && i.contains("需人工确认")), String.valueOf(c.issues()));
        assertEquals(1, c.point().assertions().get("pay").size(), "only the contract-backed status assertion is materialized");
        assertEquals("CONTRACT", c.point().generatedFrom().expectationSources().get("pay/neg_status").type());
    }

    @Test
    void invalidCandidatesAreBlockedWithReasons() {
        var noOracle = build(new NegativeCaseSuggestionService.ModelCandidate("t", "pay", Map.of("amount", -1), 400, List.of(), null, "r", List.of()));
        assertEquals("BLOCKED", noOracle.status());
        assertTrue(noOracle.issues().stream().anyMatch(i -> i.contains("状态码不足以证明")));

        var undefinedStatus = build(new NegativeCaseSuggestionService.ModelCandidate("t", "pay", Map.of("amount", -1), 409,
                List.of(new NegativeCaseSuggestionService.ModelAssertion("JSON_EXISTS", "$.code", null)), null, "r", List.of()));
        assertTrue(undefinedStatus.issues().stream().anyMatch(i -> i.contains("409")));

        var wrongType = build(new NegativeCaseSuggestionService.ModelCandidate("t", "pay", Map.of("amount", "lots"), 400,
                List.of(new NegativeCaseSuggestionService.ModelAssertion("JSON_EXISTS", "$.code", null)), null, "r", List.of()));
        assertTrue(wrongType.issues().stream().anyMatch(i -> i.contains("类型不符")));

        var secret = build(new NegativeCaseSuggestionService.ModelCandidate("t", "pay", Map.of("token", "Bearer x"), 400,
                List.of(new NegativeCaseSuggestionService.ModelAssertion("JSON_EXISTS", "$.code", null)), null, "r", List.of()));
        assertTrue(secret.issues().stream().anyMatch(i -> i.contains("敏感")));

        var undeclaredPath = build(new NegativeCaseSuggestionService.ModelCandidate("t", "pay", Map.of("amount", -1), 400,
                List.of(new NegativeCaseSuggestionService.ModelAssertion("JSON_EQUALS", "$.errorCode", "X")), null, "r", List.of()));
        assertTrue(undeclaredPath.issues().stream().anyMatch(i -> i.contains("$.errorCode")));

        var unknownRule = build(new NegativeCaseSuggestionService.ModelCandidate("t", "pay", Map.of("amount", -1), 400,
                List.of(new NegativeCaseSuggestionService.ModelAssertion("JSON_EXISTS", "$.code", null)), "order/refund", "r", List.of()));
        assertNull(unknownRule.point().intentionallyViolatedRule());
    }

    @Test
    void outOfScopeCallsAreDropped() {
        assertNull(build(new NegativeCaseSuggestionService.ModelCandidate("t", "ghost", Map.of(), 400, List.of(), null, "r", List.of())));
    }

    @Test
    void parseRejectsUnknownFieldsAndAcceptsFences() {
        assertThrows(IllegalArgumentException.class, () -> NegativeCaseSuggestionService.parse("{\"candidates\":[],\"extra\":1}"));
        assertEquals(0, NegativeCaseSuggestionService.parse("```json\n{\"candidates\":[],\"questions\":[\"q\"]}\n```").candidates().size());
        assertTrue(NegativeCaseSuggestionService.pathDeclared(Map.of("properties", Map.of("a", Map.of("type", "array", "items", Map.of("properties", Map.of("b", Map.of()))))), "$.a[0].b"));
        assertFalse(NegativeCaseSuggestionService.pathDeclared(Map.of("properties", Map.of()), "$.x"));
    }
}
