package io.yuan.octopus.scenario.testing;

import com.google.gson.Gson;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接执行 JMX 中的 assert.groovy 模板（与 JMeter 5.6.3 相同的 Groovy 3），不启动 JMeter。
 * 覆盖 Phase 1C 的 SCHEMA_CONFORMS 以及 "$.body" 路径约定（LLM 补全写 $.body.x，人工写 $.x）。
 */
class AssertTemplateTest {

    /** JMeter SampleResult 的最小替身。 */
    public static class FakeSample {
        final String body; final String code; Boolean successful; String message;
        FakeSample(String body, String code) { this.body = body; this.code = code; }
        public String getResponseDataAsString() { return body; }
        public String getResponseCode() { return code; }
        public void setSuccessful(boolean value) { successful = value; }
        public void setResponseMessage(String value) { message = value; }
    }

    public static class FakeAssertionResult {
        Boolean failure; String message;
        public void setFailure(boolean value) { failure = value; }
        public void setFailureMessage(String value) { message = value; }
    }

    public static class FakeVars {
        final Map<String, Object> objects = new HashMap<>();
        final Map<String, String> strings = new HashMap<>();
        public Object getObject(String key) { return objects.get(key); }
        public void putObject(String key, Object value) { objects.put(key, value); }
        public String get(String key) { return strings.get(key); }
        public void put(String key, String value) { strings.put(key, value); }
    }

    private static final ThreadLocal<Properties> PROPS = ThreadLocal.withInitial(Properties::new);

    @Test
    void probeEvidenceIsWrittenOnlyWhenRequested() throws IOException {
        // 设计 8.3：生成探测时写入断言路径上的实际值和输出值摘要；正式运行不写
        var file = java.nio.file.Files.createTempFile("evidence", ".jsonl");
        java.nio.file.Files.delete(file);
        var outputs = List.<Map<String, Object>>of(Map.of("key", "order.id", "path", "$.id", "type", "string", "sensitive", false));
        run(List.of(status(200), Map.of("id", "st2", "kind", "JSON_EQUALS", "path", "$.status", "expected", "PAID")), outputs, "{\"id\":\"o1\",\"status\":\"NEW\"}", "200");
        assertFalse(java.nio.file.Files.exists(file));
        var props = new Properties();
        props.setProperty("octopus.probeEvidenceFile", file.toString());
        PROPS.set(props);
        try {
            var r = run(List.of(status(200), Map.of("id", "st2", "kind", "JSON_EQUALS", "path", "$.status", "expected", "PAID")), outputs, "{\"id\":\"o1\",\"status\":\"NEW\"}", "200");
            assertEquals(Boolean.TRUE, r.assertion().failure, "evidence capture never changes the verdict");
            var line = new Gson().fromJson(java.nio.file.Files.readString(file).strip(), Map.class);
            assertEquals("s1", line.get("callId"));
            assertTrue(line.toString().contains("actual=NEW"));
            var sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("o1".getBytes(StandardCharsets.UTF_8)));
            assertTrue(line.toString().contains(sha));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        finally { PROPS.remove(); java.nio.file.Files.deleteIfExists(file); }
    }

    private record Outcome(FakeAssertionResult assertion, Map<String, Object> context, FakeSample sample, FakeVars vars) {}

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("id", "status"),
            "properties", Map.of(
                    "id", Map.of("type", "string"),
                    "status", Map.of("type", "string", "enum", List.of("PAID", "NEW"))));

    private static Outcome run(List<Map<String, Object>> assertions, List<Map<String, Object>> outputs,
                               String body, String code) throws IOException {
        return run(assertions, outputs, body, code, null, new FakeVars());
    }

    private static Outcome run(List<Map<String, Object>> assertions, List<Map<String, Object>> outputs,
                               String body, String code, Map<String, Object> poll, FakeVars vars) throws IOException {
        Map<String, Object> step = new LinkedHashMap<>();
        if (poll != null) step.put("poll", poll);
        step.put("id", "s1");
        step.put("assertions", assertions);
        step.put("outputs", outputs);
        step.put("states", List.of());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("step", step);
        spec.put("stateModels", List.of());
        spec.put("responseSchemas", Map.of("schema1", SCHEMA));

        @SuppressWarnings("unchecked")
        Map<String, Object> context = vars.getObject("oct.context") instanceof Map<?, ?> existing ? (Map<String, Object>) existing : new HashMap<>();
        vars.putObject("oct.context", context);
        var assertion = new FakeAssertionResult();
        var binding = new Binding();
        binding.setVariable("Parameters", Base64.getEncoder().encodeToString(new Gson().toJson(spec).getBytes(StandardCharsets.UTF_8)));
        var sample = new FakeSample(body, code);
        binding.setVariable("prev", sample);
        binding.setVariable("vars", vars);
        binding.setVariable("props", PROPS.get());
        binding.setVariable("AssertionResult", assertion);
        new GroovyShell(AssertTemplateTest.class.getClassLoader(), binding).evaluate(template());
        return new Outcome(assertion, context, sample, vars);
    }

    private static String template() throws IOException {
        try (InputStream in = AssertTemplateTest.class.getResourceAsStream("/testing/assert.groovy")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> status(int code) { return Map.of("id", "st", "kind", "STATUS_EQUALS", "expected", code); }
    private static Map<String, Object> schemaCheck() {
        Map<String, Object> a = new HashMap<>(); a.put("id", "schema1"); a.put("kind", "SCHEMA_CONFORMS"); a.put("path", "$"); a.put("expected", null);
        return a;
    }

    @Test
    void schemaConformingResponsePasses() throws IOException {
        var r = run(List.of(status(200), schemaCheck()), List.of(), "{\"id\":\"o1\",\"status\":\"PAID\"}", "200");
        assertEquals(Boolean.FALSE, r.assertion().failure);
    }

    @Test
    void schemaViolationFailsWithPathsButNoValues() throws IOException {
        var r = run(List.of(status(200), schemaCheck()), List.of(), "{\"id\":12345,\"status\":\"SECRET-VALUE\"}", "200");
        assertEquals(Boolean.TRUE, r.assertion().failure);
        assertTrue(r.assertion().message.contains("schema1 $.id: expected string"));
        assertTrue(r.assertion().message.contains("$.status: value not in enum"));
        assertFalse(r.assertion().message.contains("SECRET-VALUE"), "violation text must not leak response values");
    }

    @Test
    void llmBodyPrefixedPathsResolveAgainstResponseBody() throws IOException {
        var r = run(List.of(status(200), Map.of("id", "e", "kind", "JSON_EQUALS", "path", "$.body.status", "expected", "PAID")),
                List.of(Map.of("key", "order.id", "path", "$.body.id", "type", "string", "sensitive", false)),
                "{\"id\":\"o1\",\"status\":\"PAID\"}", "200");
        assertEquals(Boolean.FALSE, r.assertion().failure);
        assertEquals("o1", r.context().get("order.id"));
    }

    @Test
    void rootPathsAndLiteralBodyFieldsKeepTheirMeaning() throws IOException {
        assertEquals(Boolean.FALSE, run(List.of(status(200), Map.of("id", "e", "kind", "JSON_EQUALS", "path", "$.status", "expected", "PAID")),
                List.of(), "{\"status\":\"PAID\"}", "200").assertion().failure);
        // 回应体根上真的有 body 字段时按字面解析（兼容旧流程）
        assertEquals(Boolean.FALSE, run(List.of(status(200), Map.of("id", "e", "kind", "JSON_EQUALS", "path", "$.body.x", "expected", 1)),
                List.of(), "{\"body\":{\"x\":1}}", "200").assertion().failure);
    }

    @Test
    void wrongStatusStillFails() throws IOException {
        var r = run(List.of(status(200), schemaCheck()), List.of(), "{\"id\":\"o1\",\"status\":\"PAID\"}", "500");
        assertEquals(Boolean.TRUE, r.assertion().failure);
    }

    // ── Phase 2.1 轮询 ─────────────────────────────────────────────

    private static FakeVars pollVars() {
        var vars = new FakeVars();
        vars.put("oct.poll.s1.continue", "true");
        vars.put("oct.poll.s1.attempt", "0");
        vars.put("oct.poll.s1.start", String.valueOf(System.currentTimeMillis()));
        vars.put("oct.poll.s1.delay", "0");
        return vars;
    }

    @Test
    void pollRetriesWithoutFailingOrCommittingUntilOracleHolds() throws IOException {
        var vars = pollVars();
        var poll = Map.<String, Object>of("intervalMillis", 500, "timeoutMillis", 10000);
        var checks = List.<Map<String, Object>>of(status(200), Map.of("id", "done", "kind", "JSON_EQUALS", "path", "$.state", "expected", "DONE"));
        var outputs = List.<Map<String, Object>>of(Map.of("key", "job.result", "path", "$.result", "type", "string", "sensitive", false));

        var first = run(checks, outputs, "{\"state\":\"RUNNING\"}", "200", poll, vars);
        assertEquals(Boolean.FALSE, first.assertion().failure);
        assertEquals("POLL_RETRY", first.sample().message);
        assertEquals("true", vars.get("oct.poll.s1.continue"));
        assertEquals("500", vars.get("oct.poll.s1.delay"));
        assertFalse(first.context().containsKey("job.result"), "intermediate attempts must not commit outputs");

        var second = run(checks, outputs, "{\"state\":\"DONE\",\"result\":\"ok\"}", "200", poll, vars);
        assertEquals(Boolean.FALSE, second.assertion().failure);
        assertNull(second.sample().message);
        assertEquals("false", vars.get("oct.poll.s1.continue"));
        assertEquals("2", vars.get("oct.poll.s1.attempt"));
        assertEquals("ok", second.context().get("job.result"));
    }

    @Test
    void pollFailsOnLastAttempt() throws IOException {
        var vars = pollVars();
        var poll = Map.<String, Object>of("intervalMillis", 1000, "timeoutMillis", 1000);   // 最多 2 次
        var checks = List.<Map<String, Object>>of(status(200), Map.of("id", "done", "kind", "JSON_EQUALS", "path", "$.state", "expected", "DONE"));
        assertEquals(Boolean.FALSE, run(checks, List.of(), "{\"state\":\"RUNNING\"}", "200", poll, vars).assertion().failure);
        var last = run(checks, List.of(), "{\"state\":\"RUNNING\"}", "200", poll, vars);
        assertEquals(Boolean.TRUE, last.assertion().failure);
        assertTrue(last.assertion().message.contains("poll_timeout_after_2_attempts"));
        assertEquals("false", vars.get("oct.poll.s1.continue"));
    }
}
