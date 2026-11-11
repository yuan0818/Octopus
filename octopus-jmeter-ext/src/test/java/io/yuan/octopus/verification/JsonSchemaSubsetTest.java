package io.yuan.octopus.verification;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaSubsetTest {

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    private static final Map<String, Object> ORDER = m("type", "object", "required", List.of("id", "status"),
            "additionalProperties", false,
            "properties", m(
                    "id", m("type", "string", "minLength", 1),
                    "status", m("type", "string", "enum", List.of("PAID", "NEW")),
                    "amount", m("type", "number", "minimum", 0, "exclusiveMinimum", true),
                    "tags", m("type", "array", "items", m("type", "string"), "uniqueItems", true),
                    "note", m("type", "string", "nullable", true),
                    "count", m("type", "integer")));

    @Test
    void conformingDocumentPasses() {
        Map<String, Object> doc = new HashMap<>(m("id", "o1", "status", "PAID", "amount", new BigDecimal("1.5"),
                "tags", List.of("a", "b"), "count", 3));
        doc.put("note", null);
        assertEquals(List.of(), JsonSchemaSubset.violations(ORDER, doc));
    }

    @Test
    void reportsEachViolationWithPath() {
        List<String> v = JsonSchemaSubset.violations(ORDER, m("id", "", "status", "X", "amount", 0,
                "tags", List.of("a", "a"), "extra", 1, "count", 1.5));
        assertEquals(List.of("$.id: shorter than minLength 1", "$.status: value not in enum", "$.amount: below minimum 0",
                "$.tags: items are not unique", "$.extra: additional property not allowed", "$.count: expected integer but was number"), v);
        assertTrue(JsonSchemaSubset.violations(ORDER, m("status", "PAID")).contains("$.id: required property missing"));
    }

    @Test
    void combinatorsAndNullability() {
        Map<String, Object> one = m("oneOf", List.of(m("type", "string"), m("type", "integer")));
        assertTrue(JsonSchemaSubset.conforms(one, "x"));
        assertFalse(JsonSchemaSubset.conforms(one, 1.5));
        assertFalse(JsonSchemaSubset.conforms(m("oneOf", List.of(m("type", "number"), m("type", "integer"))), 1));
        assertTrue(JsonSchemaSubset.conforms(m("anyOf", List.of(m("type", "number"), m("type", "integer"))), 1));
        assertFalse(JsonSchemaSubset.conforms(m("not", m("type", "string")), "x"));
        assertTrue(JsonSchemaSubset.conforms(m("type", List.of("string", "null")), null));
        assertFalse(JsonSchemaSubset.conforms(m("type", "string"), null));
        assertTrue(JsonSchemaSubset.conforms(m(), null));
    }

    @Test
    void numericKeywordsBothOpenApiStyles() {
        assertTrue(JsonSchemaSubset.conforms(m("type", "number", "exclusiveMaximum", 10), 9.99));
        assertFalse(JsonSchemaSubset.conforms(m("type", "number", "exclusiveMaximum", 10), 10));
        assertFalse(JsonSchemaSubset.conforms(m("type", "number", "maximum", 10, "exclusiveMaximum", true), 10));
        assertTrue(JsonSchemaSubset.conforms(m("type", "integer"), 2.0));
        assertFalse(JsonSchemaSubset.conforms(m("type", "number", "multipleOf", 0.5), 0.75));
        assertTrue(JsonSchemaSubset.conforms(m("enum", List.of(1)), 1.0));
    }

    @Test
    void unsupportedKeywordsAndRefsAreListed() {
        assertEquals(List.of(), JsonSchemaSubset.unsupportedKeywords(ORDER));
        assertEquals(List.of("$.a:$ref", "$.b:if"), JsonSchemaSubset.unsupportedKeywords(m("type", "object",
                "properties", m("a", m("$ref", "#/x"), "b", m("type", "string", "if", m())),
                "x-internal", true, "description", "d", "format", "uuid")));
        assertEquals(List.of("$: unresolved $ref"), JsonSchemaSubset.violations(m("$ref", "#/c"), 1));
    }

    @Test
    void violationsAreCapped() {
        List<Object> big = new ArrayList<>();
        for (int i = 0; i < 500; i++) big.add(i);
        assertEquals(JsonSchemaSubset.MAX_VIOLATIONS,
                JsonSchemaSubset.violations(m("type", "array", "items", m("type", "string")), big).size());
    }
}
