package io.yuan.octopus.review.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.yuan.octopus.review.adapter.HttpLLMAdapter;
import io.yuan.octopus.review.adapter.LLMAdapter;
import io.yuan.octopus.review.controller.admin.ModelAdminController;
import io.yuan.octopus.review.dto.LLMConfig;
import io.yuan.octopus.review.dto.ResponseFormat;
import io.yuan.octopus.review.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 结构化输出原型（SO-001～SO-012，AT-S1～AT-S8）。 */
class StructuredOutputTest {

    // ------------------------------------------------------------------ AT-S7: common strict subset

    static final Set<String> FORBIDDEN = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "minItems", "maxItems", "pattern", "format", "$ref", "allOf", "oneOf", "not", "patternProperties");

    @Test
    void everySchemaStaysInsideTheCommonStrictSubset() {
        Map<String, Integer> expectedUnions = Map.of(CaseOutputSchemas.PLAN_NEW, 5, CaseOutputSchemas.PLAN_REVISE, 7,
                CaseOutputSchemas.MATERIALIZE, 1, CaseOutputSchemas.MATERIALIZE_TOOLS, 2);
        for (var entry : expectedUnions.entrySet()) {
            Map<String, Object> schema = CaseOutputSchemas.schema(entry.getKey());
            assertEquals("object", schema.get("type"), entry.getKey() + " root must be an object");
            assertFalse(schema.containsKey("anyOf"));
            int[] unions = {0};
            int depth = walk(schema, 0, entry.getKey(), unions);
            assertTrue(depth <= 5, entry.getKey() + " nests objects " + depth + " levels");
            assertEquals(entry.getValue(), unions[0], entry.getKey() + " union fields");
            assertTrue(unions[0] <= 16);
            assertDoesNotThrow(() -> ResponseFormat.jsonSchema(entry.getKey(), schema));
            JsonParser.parseString(new Gson().toJson(schema));   // serializable as plain JSON
        }
    }

    /** 返回对象嵌套层数；统计使用 anyOf 或类型数组的属性。 */
    @SuppressWarnings("unchecked")
    static int walk(Object node, int level, String where, int[] unions) {
        if (node instanceof List<?> list) { int max = level; for (Object o : list) max = Math.max(max, walk(o, level, where, unions)); return max; }
        if (!(node instanceof Map<?, ?> raw)) return level;
        Map<String, Object> map = (Map<String, Object>) raw;
        for (String key : map.keySet()) assertFalse(FORBIDDEN.contains(key), where + " uses unsupported keyword " + key);
        Object type = map.get("type");
        boolean object = "object".equals(type) || type instanceof List<?> l && l.contains("object");
        int max = level;
        if (object) {
            Map<String, Object> properties = (Map<String, Object>) map.get("properties");
            assertNotNull(properties, where);
            assertEquals(Boolean.FALSE, map.get("additionalProperties"), where + " must set additionalProperties=false");
            assertEquals(new HashSet<>(properties.keySet()), new HashSet<>((List<String>) map.get("required")), where + " must require every property");
            for (var p : properties.entrySet()) {
                Map<String, Object> property = (Map<String, Object>) p.getValue();
                if (property.containsKey("anyOf") || property.get("type") instanceof List<?>) unions[0]++;
                max = Math.max(max, walk(property, level + 1, where + "." + p.getKey(), unions));
            }
        }
        if (map.get("items") != null) {
            Map<String, Object> items = (Map<String, Object>) map.get("items");
            if (items.containsKey("anyOf") || items.get("type") instanceof List<?>) unions[0]++;
            max = Math.max(max, walk(items, level, where + "[]", unions));
        }
        if (map.get("anyOf") instanceof List<?> alternatives) for (Object a : alternatives) max = Math.max(max, walk(a, level, where, unions));
        return Math.max(max, object ? level + 1 : level);
    }

    @Test
    void revisionSchemaCannotExpressImmutableFields() {   // SO-005 / AT-S5
        @SuppressWarnings("unchecked")
        var intent = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) CaseOutputSchemas.schema(CaseOutputSchemas.PLAN_REVISE)
                .get("properties")).get("intents")).get("items");
        @SuppressWarnings("unchecked")
        var fields = ((Map<String, Object>) intent.get("properties")).keySet();
        for (String immutable : List.of("callId", "overrides", "rejectionStatus", "assertions", "category", "intentionallyViolatedRule"))
            assertFalse(fields.contains(immutable), immutable);
    }

    // ------------------------------------------------------------------ SO-006 / SO-007: normalization

    /** 与 scenario-runner StructuredOutputContractTest 共用的还原结果（两端契约）。 */
    static final String PLAN_NEW_RESPONSE = """
            {"intents":[{"title":"数量为 0","category":"NEGATIVE","callId":"create",
              "overrides":[{"input":"quantity","value":0},{"input":"quantity","value":-1}],
              "data":[{"callId":"login","input":"region","value":"cn"}],
              "slots":[{"callId":"create","input":"coupon","purpose":"VALID","uniqueKey":false}],
              "rejectionStatus":400,"assertions":[{"kind":"JSON_EQUALS","path":"$.code","expected":"INVALID_QUANTITY"}],
              "intentionallyViolatedRule":null,"rationale":"违反 minimum","evidence":["minimum=1"],"preconditionNotes":null}],
             "questions":["是否需要校验库存"]}""";
    static final String PLAN_NEW_NORMALIZED = "{\"intents\":[{\"title\":\"数量为 0\",\"category\":\"NEGATIVE\",\"callId\":\"create\","
            + "\"overrides\":{\"quantity\":-1},\"data\":{\"login\":{\"region\":\"cn\"}},\"slots\":{\"create\":{\"coupon\":{\"purpose\":\"VALID\",\"uniqueKey\":false}}},"
            + "\"rejectionStatus\":400,\"assertions\":[{\"kind\":\"JSON_EQUALS\",\"path\":\"$.code\",\"expected\":\"INVALID_QUANTITY\"}],"
            + "\"rationale\":\"违反 minimum\",\"evidence\":[\"minimum=1\"]}],\"questions\":[\"是否需要校验库存\"]}";

    @Test
    void firstRoundArraysBecomeTheExistingMapsAndNullsAreOmitted() {
        assertEquals(PLAN_NEW_NORMALIZED, CaseOutputSchemas.normalizePlan(PLAN_NEW_RESPONSE, false), "duplicate keys: the later entry wins, as in the existing reader");
        assertEquals(PLAN_NEW_NORMALIZED, CaseOutputSchemas.normalizePlan("```json\n" + PLAN_NEW_RESPONSE + "\n```", false));
    }

    @Test
    void revisionKeepsNullAsUnchangedAndEmptySlotsAsCleared() {
        String revised = CaseOutputSchemas.normalizePlan("""
                {"intents":[{"intentId":"it_1","parentIntentId":null,"title":null,"data":[{"callId":"create","input":"sku","value":"SKU-2"}],"slots":[],"preconditionNotes":null},
                            {"intentId":null,"parentIntentId":"it_2","title":"拆分：库存不足","data":null,"slots":null,"preconditionNotes":"需要库存为 0 的商品"}],
                 "drops":[{"intentId":"it_3","reason":"无法修复"}],"changes":[{"intentId":"it_1","feedbackIds":["fb_1"],"description":"换 SKU"}],"questions":[]}""", true);
        assertEquals("{\"intents\":[{\"intentId\":\"it_1\",\"data\":{\"create\":{\"sku\":\"SKU-2\"}},\"slots\":{}},"
                + "{\"parentIntentId\":\"it_2\",\"title\":\"拆分：库存不足\",\"preconditionNotes\":\"需要库存为 0 的商品\"}],"
                + "\"drops\":[{\"intentId\":\"it_3\",\"reason\":\"无法修复\"}],\"changes\":[{\"intentId\":\"it_1\",\"feedbackIds\":[\"fb_1\"],\"description\":\"换 SKU\"}],"
                + "\"questions\":[]}", revised);
        assertNull(CaseOutputSchemas.normalizePlan("{\"intents\":[{\"intentId\":\"it_1\",\"callId\":\"other\"}]}", true),
                "an immutable field in a revision is not the schema shape");
    }

    @Test
    void contentThatIsNotTheSchemaShapeIsReturnedUntouched() {
        for (String content : List.of("", "抱歉，我无法完成", "{\"intents\":[{\"title\":\"x\"", "[]",
                "{\"intents\":[{\"title\":\"x\",\"overrides\":{\"quantity\":0}}]}",          // existing map shape
                "{\"intents\":[],\"extra\":1}"))
            assertNull(CaseOutputSchemas.normalizePlan(content, false), content);
        assertNull(CaseOutputSchemas.normalizeMaterialize("{\"bindings\":{\"create\":{\"q\":{\"source\":\"literal\",\"value\":1}}}}"));
    }

    static final String MATERIALIZE_NORMALIZED = "{\"bindings\":{\"create\":{\"coupon\":{\"source\":\"literal\",\"value\":\"CP-1234\"},\"region\":{\"source\":\"context\",\"value\":\"run.region\"}}}}";

    @Test
    void operatorResponsesBecomeToolCallsOrBindings() {
        assertEquals("{\"toolCalls\":[{\"name\":\"search_functions\",\"arguments\":{\"query\":\"uuid\"}},"
                + "{\"name\":\"describe_function\",\"arguments\":{\"name\":\"uuid@1\"}},"
                + "{\"name\":\"try_function\",\"arguments\":{\"name\":\"pad@1\",\"arguments\":{\"width\":4,\"text\":\"7\"}}}]}",
                CaseOutputSchemas.normalizeMaterialize("""
                        {"toolCalls":[{"name":"search_functions","query":"uuid","stage":"","function":"","arguments":[]},
                                      {"name":"describe_function","query":"","stage":"","function":"uuid@1","arguments":[]},
                                      {"name":"try_function","query":"","stage":"","function":"pad@1","arguments":[{"name":"width","value":4},{"name":"text","value":"7"}]}],
                         "bindings":[]}"""));
        assertEquals(MATERIALIZE_NORMALIZED, CaseOutputSchemas.normalizeMaterialize("""
                {"toolCalls":[],"bindings":[{"callId":"create","input":"coupon","source":"literal","value":"CP-1234"},
                                            {"callId":"create","input":"region","source":"context","value":"run.region"}]}"""));
        assertEquals(MATERIALIZE_NORMALIZED, CaseOutputSchemas.normalizeMaterialize("""
                        {"bindings":[{"callId":"create","input":"coupon","source":"literal","value":"CP-1234"},
                                     {"callId":"create","input":"region","source":"context","value":"run.region"}]}"""), "schema without tools");
    }

    // ------------------------------------------------------------------ service: mode, prompt, schema, version

    static final String PLAN_PROMPT_SHA256 = "4f72fa9c533b3c9681ebadeb7907aad572c6d487f9eea2f8137a8835f8f297bf";
    static final String MATERIALIZE_PROMPT_SHA256 = "37accecb54d31fc53e1076933b83c6b7e9d559a45238497af8a9846b7a12f0d5";

    @Test
    void existingPromptsAreByteForByteUnchanged() throws Exception {   // AT-S1
        var md = java.security.MessageDigest.getInstance("SHA-256");
        assertEquals(PLAN_PROMPT_SHA256, HexFormat.of().formatHex(md.digest(BusinessFlowSuggestionService.CASE_PLAN_PROMPT.getBytes(StandardCharsets.UTF_8))));
        assertEquals(MATERIALIZE_PROMPT_SHA256, HexFormat.of().formatHex(md.digest(BusinessFlowSuggestionService.CASE_MATERIALIZE_PROMPT.getBytes(StandardCharsets.UTF_8))));
    }

    record Fixture(BusinessFlowSuggestionService service, LLMIntegrationService llm) {}

    static Fixture fixture(String apiFormat, String mode, String reply) {
        var registry = mock(ModelRegistry.class);
        var llm = mock(LLMIntegrationService.class);
        ModelConfig model = new ModelConfig("configured", "claude".equals(apiFormat) ? "claude" : "openai", "fixture-model", "https://model.example.com", 20000, 0.4);
        model.setApiKey("fixture-api-key");
        model.setApiFormat(apiFormat);
        model.setStructuredOutput(mode);
        when(registry.getAllConfigs()).thenReturn(Map.of(model.getName(), model));
        when(llm.callOnce(any(), any(), any())).thenReturn(new LLMAdapter.LLMResponse(reply, "fixture-model", 10, 10));
        return new Fixture(new BusinessFlowSuggestionService(registry, llm), llm);
    }

    static Map<String, Object> planContext(int round) {
        Map<String, Object> context = new HashMap<>(Map.of("calls", List.of(Map.of("id", "create")), "basePoint", Map.of("id", "happy"), "limit", 5, "round", round));
        if (round > 1) { context.put("previousPlan", List.of()); context.put("feedback", List.of(Map.of("feedbackId", "fb_1"))); }
        return context;
    }

    record Call(String system, LLMConfig config) {}

    static Call captured(LLMIntegrationService llm) {
        var system = ArgumentCaptor.forClass(String.class);
        var config = ArgumentCaptor.forClass(LLMConfig.class);
        verify(llm, atLeastOnce()).callOnce(system.capture(), any(), config.capture());
        return new Call(system.getValue(), config.getValue());
    }

    @Test
    void schemaModeUsesTheFixedSchemaTheSchemaPromptAndNormalizesTheReply() {   // AT-S2 / AT-S3
        for (String apiFormat : List.of("openai", "claude")) {
            var f = fixture(apiFormat, "json_schema", PLAN_NEW_RESPONSE);
            var response = f.service().casePlan(new BusinessFlowSuggestionService.VerificationRequest("", planContext(1)));
            assertEquals("case-plan@1+schema@1", response.promptVersion());
            assertEquals(PLAN_NEW_NORMALIZED, response.content());
            var call = captured(f.llm());
            assertEquals(ResponseFormat.JSON_SCHEMA, call.config().getResponseFormat().type());
            assertEquals(CaseOutputSchemas.PLAN_NEW, call.config().getResponseFormat().name());
            assertSame(CaseOutputSchemas.schema(CaseOutputSchemas.PLAN_NEW), call.config().getResponseFormat().schema());
            assertEquals(BusinessFlowSuggestionService.CASE_PLAN_RULES + CaseOutputSchemas.PLAN_NEW_TAIL, call.system());
        }
        var revise = fixture("openai", "json_schema", "{\"intents\":[],\"drops\":[],\"changes\":[],\"questions\":[]}");
        assertEquals("{\"intents\":[],\"drops\":[],\"changes\":[],\"questions\":[]}",
                revise.service().casePlan(new BusinessFlowSuggestionService.VerificationRequest("", planContext(2))).content());
        assertEquals(CaseOutputSchemas.PLAN_REVISE, captured(revise.llm()).config().getResponseFormat().name());
        assertTrue(captured(revise.llm()).system().endsWith(CaseOutputSchemas.PLAN_REVISE_TAIL));
    }

    @Test
    void jsonModeOnlyAddsTheRequestFlagAndNoneChangesNothing() {   // AT-S1
        var json = fixture("openai", "json_object", "{\"intents\":[]}");
        var response = json.service().casePlan(new BusinessFlowSuggestionService.VerificationRequest("", planContext(1)));
        assertEquals("case-plan@1+json_object", response.promptVersion());
        assertEquals("{\"intents\":[]}", response.content());
        assertEquals(ResponseFormat.JSON_OBJECT, captured(json.llm()).config().getResponseFormat().type());
        assertEquals(BusinessFlowSuggestionService.CASE_PLAN_PROMPT, captured(json.llm()).system());
        for (String mode : Arrays.asList(null, "none", "bogus")) {
            var none = fixture("openai", mode, "{\"intents\":[]}");
            assertEquals(BusinessFlowSuggestionService.CASE_PLAN_VERSION,
                    none.service().casePlan(new BusinessFlowSuggestionService.VerificationRequest("", planContext(1))).promptVersion());
            assertNull(captured(none.llm()).config().getResponseFormat());
            assertEquals(BusinessFlowSuggestionService.CASE_PLAN_PROMPT, captured(none.llm()).system());
        }
        // 存量的 Anthropic + json_object 配置按不启用处理，而不是发送无效请求
        var legacy = fixture("claude", "json_object", "{\"intents\":[]}");
        assertEquals(BusinessFlowSuggestionService.CASE_PLAN_VERSION,
                legacy.service().casePlan(new BusinessFlowSuggestionService.VerificationRequest("", planContext(1))).promptVersion());
        assertNull(captured(legacy.llm()).config().getResponseFormat());
    }

    @Test
    void operatorSchemaDependsOnWhetherFunctionToolsAreOffered() {
        Map<String, Object> slots = Map.of("create", Map.of("coupon", Map.of("purpose", "VALID")));
        var plain = fixture("openai", "json_schema", "{\"bindings\":[{\"callId\":\"create\",\"input\":\"coupon\",\"source\":\"literal\",\"value\":\"CP-1234\"}]}");
        var filled = plain.service().caseMaterialize(new BusinessFlowSuggestionService.VerificationRequest("", Map.of("slots", slots)));
        assertEquals("case-materialize@1+schema@1", filled.promptVersion());
        assertEquals("{\"bindings\":{\"create\":{\"coupon\":{\"source\":\"literal\",\"value\":\"CP-1234\"}}}}", filled.content());
        assertEquals(CaseOutputSchemas.MATERIALIZE, captured(plain.llm()).config().getResponseFormat().name());
        assertTrue(captured(plain.llm()).system().contains("不得修改数据位以外的输入"), "the safety rules survive the prompt split");

        var tools = fixture("openai", "json_schema", "{\"toolCalls\":[],\"bindings\":[]}");
        tools.service().caseMaterialize(new BusinessFlowSuggestionService.VerificationRequest("", Map.of("slots", slots, "functionTools", List.of(Map.of("name", "search_functions")))));
        assertEquals(CaseOutputSchemas.MATERIALIZE_TOOLS, captured(tools.llm()).config().getResponseFormat().name());
        assertTrue(captured(tools.llm()).system().contains("不得修改数据位以外的输入"));
    }

    @Test
    void unparsableStructuredRepliesAreReturnedAsIsForBoundedCorrection() {   // AT-S6
        var f = fixture("openai", "json_schema", "{\"intents\":[{\"title\":\"截断");
        assertEquals("{\"intents\":[{\"title\":\"截断",
                f.service().casePlan(new BusinessFlowSuggestionService.VerificationRequest("", planContext(1))).content());
        verify(f.llm(), times(1)).callOnce(any(), any(), any());   // 不重试
    }

    // ------------------------------------------------------------------ SO-002 / AT-S4: configuration

    @Test
    void anthropicFormatCannotUseJsonMode() {
        assertThrows(IllegalArgumentException.class, () -> StructuredOutputMode.normalize("claude", "anything", "json_object"));
        assertThrows(IllegalArgumentException.class, () -> StructuredOutputMode.normalize(null, "claude", "json_object"));
        assertThrows(IllegalArgumentException.class, () -> StructuredOutputMode.normalize("openai", "openai", "xml"));
        assertEquals("json_schema", StructuredOutputMode.normalize("claude", "claude", " JSON_SCHEMA "));
        assertEquals("json_object", StructuredOutputMode.normalize("openai", "deepseek", "json_object"));
        assertNull(StructuredOutputMode.normalize("openai", "deepseek", "none"));
        assertNull(StructuredOutputMode.normalize("openai", "deepseek", null));

        var registry = mock(ModelRegistry.class);
        var controller = new ModelAdminController(registry, mock(ModelTester.class), mock(SecretCipher.class));
        ModelConfig claude = new ModelConfig("c", "claude", "claude-sonnet-5-5", "https://api.anthropic.com/v1", 16000, 0.2);
        claude.setApiKey("k");
        claude.setStructuredOutput("json_object");
        var rejected = controller.registerModel(claude);
        assertEquals(400, rejected.code());
        assertTrue(rejected.message().contains("Anthropic"));
        verify(registry, never()).register(any());
        claude.setStructuredOutput("JSON_SCHEMA");
        assertTrue(controller.updateModel("c", claude).success());
        var saved = ArgumentCaptor.forClass(ModelConfig.class);
        verify(registry).register(saved.capture());
        assertEquals("json_schema", saved.getValue().getStructuredOutput());
    }

    // ------------------------------------------------------------------ AT-S1 ~ AT-S3: request bodies on the wire

    record Stub(com.sun.net.httpserver.HttpServer server, List<JsonObject> bodies, List<String> paths) implements AutoCloseable {
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        @Override public void close() { server.stop(0); }
    }

    static Stub stub(Function<JsonObject, Object> reply) throws Exception {
        var bodies = new CopyOnWriteArrayList<JsonObject>();
        var paths = new CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            bodies.add(body);
            paths.add(exchange.getRequestURI().getPath());
            Object answer = reply.apply(body);
            int status = answer instanceof Integer code ? code : 200;
            byte[] bytes = (answer instanceof Integer ? "{}" : new Gson().toJson(answer)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new Stub(server, bodies, paths);
    }

    static Map<String, Object> openaiReply(Object content, Object refusal) {
        Map<String, Object> message = new HashMap<>();
        message.put("content", content);
        if (refusal != null) message.put("refusal", refusal);
        return Map.of("choices", List.of(Map.of("message", message)), "usage", Map.of("prompt_tokens", 3, "completion_tokens", 2));
    }

    static Map<String, Object> claudeReply(String text) {
        return Map.of("content", List.of(Map.of("type", "thinking", "thinking", "…"), Map.of("type", "text", "text", text)),
                "usage", Map.of("input_tokens", 3, "output_tokens", 2));
    }

    static LLMConfig config(Stub stub, String provider, ResponseFormat format) {
        var config = new LLMConfig();
        config.setProvider(provider);
        config.setApiFormat("claude".equals(provider) ? "claude" : "openai");
        config.setModel("fixture-model");
        config.setApiKey("fixture-key");
        config.setApiUrl(stub.url());
        config.setMaxTokens(100);
        config.setResponseFormat(format);
        return config;
    }

    @Test
    void openaiCompatibleRequestsCarryResponseFormatOnlyWhenEnabled() throws Exception {
        try (var stub = stub(body -> openaiReply("{\"intents\":[]}", null))) {
            var adapter = new HttpLLMAdapter();
            adapter.call("s", "u", config(stub, "openai", null));
            adapter.call("s", "u", config(stub, "openai", ResponseFormat.jsonObject()));
            adapter.call("s", "u", config(stub, "openai", ResponseFormat.jsonSchema(CaseOutputSchemas.PLAN_NEW, CaseOutputSchemas.schema(CaseOutputSchemas.PLAN_NEW))));
            assertEquals(Set.of("model", "max_tokens", "temperature", "messages"), stub.bodies().get(0).keySet());
            assertEquals("json_object", stub.bodies().get(1).getAsJsonObject("response_format").get("type").getAsString());
            var schema = stub.bodies().get(2).getAsJsonObject("response_format");
            assertEquals("json_schema", schema.get("type").getAsString());
            assertEquals(CaseOutputSchemas.PLAN_NEW, schema.getAsJsonObject("json_schema").get("name").getAsString());
            assertTrue(schema.getAsJsonObject("json_schema").get("strict").getAsBoolean());
            assertEquals(JsonParser.parseString(new Gson().toJson(CaseOutputSchemas.schema(CaseOutputSchemas.PLAN_NEW))),
                    schema.getAsJsonObject("json_schema").get("schema"));
            assertTrue(stub.paths().stream().allMatch(p -> p.equals("/v1/chat/completions")));
        }
        try (var stub = stub(body -> openaiReply(null, "I can't help with that."))) {
            assertEquals("I can't help with that.", new HttpLLMAdapter().call("s", "u",
                    config(stub, "openai", ResponseFormat.jsonSchema("x", Map.of("type", "object")))).content(), "a strict-mode refusal goes to bounded correction");
        }
    }

    @Test
    void anthropicRequestsUseOutputConfigAndReadTheFirstTextBlock() throws Exception {
        try (var stub = stub(body -> claudeReply("{\"ok\":true}"))) {
            var adapter = new HttpLLMAdapter();
            assertEquals("{\"ok\":true}", adapter.call("s", "u", config(stub, "claude", null)).content());
            adapter.call("s", "u", config(stub, "claude", ResponseFormat.jsonSchema(CaseOutputSchemas.MATERIALIZE, CaseOutputSchemas.schema(CaseOutputSchemas.MATERIALIZE))));
            assertEquals(Set.of("model", "max_tokens", "temperature", "system", "messages"), stub.bodies().get(0).keySet());
            var format = stub.bodies().get(1).getAsJsonObject("output_config").getAsJsonObject("format");
            assertEquals("json_schema", format.get("type").getAsString());
            assertEquals(JsonParser.parseString(new Gson().toJson(CaseOutputSchemas.schema(CaseOutputSchemas.MATERIALIZE))), format.get("schema"));
            assertFalse(stub.bodies().get(1).has("response_format"));
            assertTrue(stub.paths().stream().allMatch(p -> p.equals("/v1/messages")));
        }
    }

    // ------------------------------------------------------------------ SO-012: model test

    @Test
    void modelTestAlsoChecksThatTheEndpointAcceptsTheStructuredMode() throws Exception {
        try (var stub = stub(body -> body.has("response_format") ? openaiReply("{\"ok\":true}", null) : openaiReply("OK", null))) {
            var model = new ModelConfig("m", "openai", "fixture-model", stub.url(), 100, 0.0);
            model.setApiKey("k");
            model.setApiFormat("openai");
            model.setStructuredOutput("json_schema");
            var result = new ModelTester(mock(ModelRegistry.class)).test(model);
            assertTrue(result.success(), result.message());
            assertEquals("ACCEPTED", result.detail().get("structuredOutput"));
            assertEquals(2, stub.bodies().size());
            assertEquals("octopus_probe", stub.bodies().get(1).getAsJsonObject("response_format").getAsJsonObject("json_schema").get("name").getAsString());
        }
        try (var stub = stub(body -> body.has("response_format") ? (Object) 400 : openaiReply("OK", null))) {
            var model = new ModelConfig("m", "openai", "fixture-model", stub.url(), 100, 0.0);
            model.setApiKey("k");
            model.setApiFormat("openai");
            model.setStructuredOutput("json_object");
            var result = new ModelTester(mock(ModelRegistry.class)).test(model);
            assertFalse(result.success());
            assertTrue(result.message().contains("不接受结构化输出"), result.message());
            assertEquals("REJECTED", result.detail().get("structuredOutput"));
        }
        try (var stub = stub(body -> openaiReply("OK", null))) {
            var model = new ModelConfig("m", "openai", "fixture-model", stub.url(), 100, 0.0);
            model.setApiKey("k");
            model.setApiFormat("openai");
            var result = new ModelTester(mock(ModelRegistry.class)).test(model);
            assertTrue(result.success());
            assertEquals(1, stub.bodies().size(), "no extra request when structured output is off");
        }
    }
}
