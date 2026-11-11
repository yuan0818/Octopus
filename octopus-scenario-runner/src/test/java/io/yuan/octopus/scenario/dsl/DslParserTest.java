package io.yuan.octopus.scenario.dsl;

import static org.junit.jupiter.api.Assertions.*;

import io.yuan.octopus.scenario.dsl.DslModel.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** FR-PARSE / FR-LANG / FR-LIN-02 / FR-RT-03: deterministic parsing, all diagnostics at once, canonical rendering. */
class DslParserTest {
    static final String REFUND = String.join("\n",
            "feature: 退款",
            "project: shop-platform/order-service",
            "scenarios:",
            "  - name: 已支付订单可以申请退款",
            "    tags: [refund, smoke]",
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
            "          reason: \"质量问题\"",
            "        headers:",
            "          X-Request-Id: ${fn.uuid()}",
            "          Authorization: Bearer ${secret.token}",
            "        as: refund",
            "        expect:",
            "          - status 200",
            "          - $.state == \"REFUND_PENDING\"",
            "          - $.id exists",
            "      - call: GET /refunds/{id}",
            "        with: { id: \"${refund.id}\" }",
            "        wait: 5s",
            "        expect: { status: 200, $.amount: 100 }",
            "    cleanup:",
            "      - call: DELETE /orders/{id}",
            "        with: { id: \"${order.id}\" }",
            "");

    private static Set<String> codes(Document doc, String severity) {
        return doc.diagnostics.stream().filter(d -> d.severity.equals(severity)).map(d -> d.code).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void parsesDeclaredItemsWithTypesAndPositions() {
        var doc = DslParser.parse(REFUND);
        assertFalse(doc.hasErrors(), String.valueOf(doc.diagnostics));
        var s = doc.scenarios.get(0);
        assertEquals(List.of("refund", "smoke"), s.tags);
        assertEquals(4, s.steps.size());
        var create = s.steps.get(0);
        assertEquals("POST", create.method);
        assertEquals("/orders", create.path);
        assertEquals(100L, ((Literal) create.with.get("amount")).value);
        assertEquals("order", create.alias);
        var pay = s.steps.get(1);
        var ref = (Ref) pay.with.get("id");
        assertEquals(Ref.Kind.STEP, ref.kind);
        assertEquals("order", ref.name);
        assertEquals(".id", ref.path);
        assertEquals(12, ref.line);
        var refund = s.steps.get(2);
        assertTrue(refund.headers.get("X-Request-Id") instanceof Call);
        var auth = (Interpolation) refund.headers.get("Authorization");
        assertEquals("Bearer ", auth.parts.get(0));
        assertEquals(Ref.Kind.SECRET, ((Ref) auth.parts.get(1)).kind);
        assertEquals(Expect.Kind.STATUS, refund.expects.get(0).kind);
        assertEquals("REFUND_PENDING", refund.expects.get(1).expected);
        assertEquals(Expect.Kind.JSON_EXISTS, refund.expects.get(2).kind);
        var query = s.steps.get(3);
        assertEquals(5_000, query.waitMillis);
        assertEquals(Integer.valueOf(200), DslChecker.status(query));
        assertEquals(100L, query.expects.get(1).expected);
        assertEquals(1, s.cleanup.size());
        assertEquals(List.of("GIVEN", "GIVEN", "WHEN", "THEN"), DslChecker.roles(s.steps, null));
    }

    @Test
    void reportsEveryProblemWithPositionInOnePass() {
        var doc = DslParser.parse(String.join("\n",
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /pay",
                "        with:",
                "          orderId: ${order.id}",
                "      - call: POST /orders",
                "        as: order",
                "        expect: [status 200, status 201]",
                "      - call: POST /refunds",
                "        with:",
                "          tenant: ${input.tenant}",
                "        expect:",
                "          - $.state != \"X\"",
                ""));
        var errors = codes(doc, "ERROR");
        for (String code : List.of("REF_NOT_UPSTREAM", "STATUS_DUPLICATE", "INPUT_UNDECLARED", "EXPECT_OPERATOR_UNSUPPORTED"))
            assertTrue(errors.contains(code), "missing " + code + " in " + errors);
        var first = doc.diagnostics.stream().filter(d -> d.code.equals("REF_NOT_UPSTREAM")).findFirst().orElseThrow();
        assertEquals(7, first.line);
        assertNotNull(first.hint);
    }

    @Test
    void controlFlowIsRejected() {
        var doc = DslParser.parse(String.join("\n",
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /orders",
                "        retry: 3",
                "      - call: GET /orders",
                "        if: ${order.paid}",
                "        parallel: true",
                ""));
        assertEquals(3, doc.diagnostics.stream().filter(d -> d.code.equals("LINEAR_FORBIDDEN")).count(), String.valueOf(doc.diagnostics));
    }

    @Test
    void negativeStepsMustBeCompleteAndLast() {
        var doc = DslParser.parse(String.join("\n",
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /refunds",
                "        expect: status 409",
                "      - GET /orders",
                ""));
        var errors = codes(doc, "ERROR");
        assertTrue(errors.contains("NEGATIVE_INCOMPLETE"));
        assertTrue(errors.contains("NEGATIVE_NOT_LAST"));
        var ok = DslParser.parse(String.join("\n",
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - POST /orders",
                "      - call: POST /refunds",
                "        expect: { status: 409, $.code: ORDER_NOT_PAID }",
                ""));
        assertFalse(ok.hasErrors(), String.valueOf(ok.diagnostics));
        assertEquals("ORDER_NOT_PAID", ok.scenarios.get(0).steps.get(1).expects.get(1).expected);
    }

    @Test
    void functionPositionsAndNaturalLanguageAreDiagnosed() {
        var doc = DslParser.parse(String.join("\n",
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: GET /orders/{id}",
                "        with:",
                "          id: ${fn.uuid()}",
                "        query:",
                "          at: ${fn.jm.time(\"yyyy\")}",
                "        expect:",
                "          - 订单状态为已支付",
                "          - db: orders 表中状态为 PAID",
                ""));
        assertEquals(2, doc.diagnostics.stream().filter(d -> d.code.equals("FN_POSITION")).count());
        assertEquals(2, doc.diagnostics.stream().filter(d -> d.code.equals("EXPECT_NEEDS_DERIVATION")).count());
        var expects = doc.scenarios.get(0).steps.get(0).expects;
        assertEquals(Expect.Kind.NATURAL, expects.get(0).kind);
        assertEquals(Expect.Kind.DATASTORE, expects.get(1).kind);
        assertEquals("db", expects.get(1).target);
    }

    @Test
    void yamlErrorsGetAHintAndUnknownKeysAreListed() {
        var doc = DslParser.parse("feature: x\nscenarios:\n  - name: a\n    steps:\n      - call: POST /a\n        with: { id: ${order.id} }\n");
        assertEquals("YAML_SYNTAX", doc.diagnostics.get(0).code);
        assertTrue(doc.diagnostics.get(0).hint.contains("双引号"));
        var unknown = DslParser.parse("feature: x\nscenarios:\n  - name: a\n    steps:\n      - call: POST /a\n        timeout: 3\n");
        assertTrue(codes(unknown, "ERROR").contains("UNKNOWN_KEY"));
    }

    @Test
    void literalEscapesAndYamlTyping() {
        var doc = DslParser.parse(String.join("\n",
                "feature: x",
                "scenarios:",
                "  - name: a",
                "    steps:",
                "      - call: POST /a",
                "        with:",
                "          text: \"$${not.a.ref}\"",
                "          quoted: \"100\"",
                "          country: NO",
                "          flag: true",
                "          price: 9.90",
                ""));
        assertFalse(doc.hasErrors(), String.valueOf(doc.diagnostics));
        var with = doc.scenarios.get(0).steps.get(0).with;
        assertEquals("${not.a.ref}", ((Literal) with.get("text")).value);
        assertEquals("100", ((Literal) with.get("quoted")).value);
        assertEquals("NO", ((Literal) with.get("country")).value);
        assertEquals(Boolean.TRUE, ((Literal) with.get("flag")).value);
        assertEquals(new java.math.BigDecimal("9.90"), ((Literal) with.get("price")).value);
    }

    @Test
    void canonicalRenderingRoundTrips() {
        var doc = DslParser.parse(REFUND);
        String once = DslRenderer.render(doc);
        var again = DslParser.parse(once);
        assertFalse(again.hasErrors(), once + "\n" + again.diagnostics);
        assertEquals(once, DslRenderer.render(again));
        assertTrue(once.contains("      - call: POST /orders\n"));
        assertTrue(once.contains("X-Request-Id: ${fn.uuid()}\n"));
        assertTrue(once.contains("'$.state == \"REFUND_PENDING\"'"));
    }
}
