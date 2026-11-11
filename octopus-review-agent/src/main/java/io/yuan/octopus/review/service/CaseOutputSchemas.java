package io.yuan.octopus.review.service;

import com.google.gson.*;
import java.util.*;

/**
 * 三角色用例生成的结构化输出（SO-003～SO-009）。
 * <ul>
 *   <li>4 个固定 schema，按公开文档落在 OpenAI 与 Anthropic 严格模式的公共子集内（其他端点请先用模型测试确认）：对象一律 additionalProperties=false、
 *       属性全部必填（可空用 null）、不用数值／长度约束；调用 ID 与端口名不进入 schema，便于提供方缓存编译结果。</li>
 *   <li>严格模式不允许任意键的对象，因此键值对象改为条目数组；{@link #normalizePlan} 与 {@link #normalizeMaterialize}
 *       把响应还原为现有约定，scenario-runner 的读取与校验不变。无法还原时返回 null，由调用方原样返回模型内容。</li>
 * </ul>
 */
public final class CaseOutputSchemas {
    private CaseOutputSchemas() {}

    public static final String VERSION = "schema@1";
    public static final String PLAN_NEW = "case_plan_new";
    public static final String PLAN_REVISE = "case_plan_revise";
    public static final String MATERIALIZE = "case_materialize";
    public static final String MATERIALIZE_TOOLS = "case_materialize_tools";

    // ------------------------------------------------------------------ schema building blocks

    static Map<String, Object> type(String type) { return Map.of("type", type); }
    static Map<String, Object> string() { return type("string"); }
    static Map<String, Object> nullable(String type) { return Map.of("type", List.of(type, "null")); }
    static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    static Map<String, Object> nullableArray(Map<String, Object> items) { return Map.of("type", List.of("array", "null"), "items", items); }
    static Map<String, Object> oneOf(String... values) { return Map.of("type", "string", "enum", List.of(values)); }

    /** 字面值：只允许标量（SO-009）。null 在现有校验中本来就是“类型不符”，因此不接受。 */
    static final Map<String, Object> LITERAL = Map.of("anyOf", List.of(type("string"), type("number"), type("boolean")));

    static Map<String, Object> object(Object... nameSchemaPairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < nameSchemaPairs.length; i += 2) properties.put((String) nameSchemaPairs[i], nameSchemaPairs[i + 1]);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    static final Map<String, Object> DATA_ENTRY = object("callId", string(), "input", string(), "value", LITERAL);
    static final Map<String, Object> SLOT_ENTRY = object("callId", string(), "input", string(),
            "purpose", oneOf("VALID", "VIOLATING"), "uniqueKey", type("boolean"));
    static final Map<String, Object> BINDING_ENTRY = object("callId", string(), "input", string(),
            "source", oneOf("literal", "context"), "value", LITERAL);

    static final Map<String, Object> PLAN_NEW_SCHEMA = object(
            "intents", array(object(
                    "title", string(),
                    "category", string(),
                    "callId", string(),
                    "overrides", array(object("input", string(), "value", LITERAL)),
                    "data", array(DATA_ENTRY),
                    "slots", array(SLOT_ENTRY),
                    "rejectionStatus", type("integer"),
                    "assertions", array(object("kind", oneOf("JSON_EQUALS", "JSON_EXISTS"), "path", string(), "expected", LITERAL)),
                    "intentionallyViolatedRule", nullable("string"),
                    "rationale", string(),
                    "evidence", array(string()),
                    "preconditionNotes", nullable("string"))),
            "questions", array(string()));

    /** 修订轮只能表达可修改的字段（SO-005）：不可变字段不在 schema 中。 */
    static final Map<String, Object> PLAN_REVISE_SCHEMA = object(
            "intents", array(object(
                    "intentId", nullable("string"),
                    "parentIntentId", nullable("string"),
                    "title", nullable("string"),
                    "data", nullableArray(DATA_ENTRY),
                    "slots", nullableArray(SLOT_ENTRY),
                    "preconditionNotes", nullable("string"))),
            "drops", array(object("intentId", string(), "reason", string())),
            "changes", array(object("intentId", string(), "feedbackIds", array(string()), "description", string())),
            "questions", array(string()));

    static final Map<String, Object> MATERIALIZE_SCHEMA = object("bindings", array(BINDING_ENTRY));

    static final Map<String, Object> MATERIALIZE_TOOLS_SCHEMA = object(
            "toolCalls", array(object(
                    "name", oneOf("search_functions", "describe_function", "try_function"),
                    "query", string(),
                    "stage", string(),
                    "function", string(),
                    "arguments", array(object("name", string(), "value", LITERAL)))),
            "bindings", array(BINDING_ENTRY));

    public static Map<String, Object> schema(String name) {
        return switch (name) {
            case PLAN_NEW -> PLAN_NEW_SCHEMA;
            case PLAN_REVISE -> PLAN_REVISE_SCHEMA;
            case MATERIALIZE -> MATERIALIZE_SCHEMA;
            case MATERIALIZE_TOOLS -> MATERIALIZE_TOOLS_SCHEMA;
            default -> throw new IllegalArgumentException("未知 schema: " + name);
        };
    }

    // ------------------------------------------------------------------ prompt tails (replace the JSON example paragraph)

    static final String PLAN_NEW_TAIL = """
            输出结构由平台提供的 JSON Schema 约束，以本段为准；上文规则中的键值对象写法在输出中改为条目数组：
            overrides 为 [{"input":"端口名","value":字面值}]；data 为 [{"callId":"...","input":"端口名","value":字面值}]；
            slots 为 [{"callId":"...","input":"端口名","purpose":"VALID|VIOLATING","uniqueKey":false}]。
            字面值只能是字符串、数字或布尔值。intentionallyViolatedRule 与 preconditionNotes 不适用时填 null；没有内容的数组填 []。
            只输出 JSON，不带 Markdown 围栏。
            """;

    static final String PLAN_REVISE_TAIL = """
            输出结构由平台提供的 JSON Schema 约束，以本段为准；上文规则中的键值对象写法在输出中改为条目数组：
            data 为 [{"callId":"...","input":"端口名","value":字面值}]；slots 为 [{"callId":"...","input":"端口名","purpose":"VALID|VIOLATING","uniqueKey":false}]。
            intents 中每项只能包含 intentId、parentIntentId、title、data、slots、preconditionNotes：
            修订已有意图时填 intentId、parentIntentId 为 null；拆分时 intentId 为 null、parentIntentId 为被拆分的意图；
            不修改的字段填 null（data 或 slots 为 null 表示不修改，slots 为 [] 表示清空数据位）。
            drops、changes、questions 没有内容时填 []。只输出 JSON，不带 Markdown 围栏。
            """;

    static final String MATERIALIZE_TAIL = """
            输出结构由平台提供的 JSON Schema 约束，以本段为准：bindings 为 [{"callId":"...","input":"端口名","source":"literal|context","value":取值}]，
            source 为 context 时 value 是 availableContext 中的变量名。字面值只能是字符串、数字或布尔值。只输出 JSON，不带 Markdown 围栏。
            source 只能是 literal（普通字面量，类型与端口一致）或 context（availableContext 中的变量名）。
            不得修改数据位以外的输入，不得输出凭据、令牌、真实个人数据、可执行脚本或 JMeter 函数表达式字符串。
            """;

    static final String MATERIALIZE_TOOLS_TAIL = """
            输出结构由平台提供的 JSON Schema 约束，以本段为准：
            需要调用工具时，toolCalls 非空、bindings 为 []；每个工具调用填 name 与参数字段：search_functions 用 query（stage 可留空），
            describe_function 与 try_function 用 function（函数名，形如 id@version），try_function 的参数放在 arguments：[{"name":"参数名","value":字面值}]；
            不适用的字符串字段填 ""，不适用的数组填 []。
            得到取值后 toolCalls 为 []，bindings 为 [{"callId":"...","input":"端口名","source":"literal|context","value":取值}]，
            source 为 context 时 value 是 availableContext 中的变量名。字面值只能是字符串、数字或布尔值。只输出 JSON，不带 Markdown 围栏。
            source 只能是 literal（普通字面量，类型与端口一致）或 context（availableContext 中的变量名）。
            不得修改数据位以外的输入，不得输出凭据、令牌、真实个人数据、可执行脚本或 JMeter 函数表达式字符串。
            """;

    // ------------------------------------------------------------------ normalization (SO-006, SO-007)

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> NEW_FIELDS = Set.of("title", "category", "callId", "overrides", "data", "slots", "rejectionStatus",
            "assertions", "intentionallyViolatedRule", "rationale", "evidence", "preconditionNotes");
    private static final Set<String> REVISE_FIELDS = Set.of("intentId", "parentIntentId", "title", "data", "slots", "preconditionNotes");

    /** 还原 Planner 响应；不能还原时返回 null。 */
    public static String normalizePlan(String content, boolean revise) {
        try {
            JsonObject root = root(content, revise ? Set.of("intents", "drops", "changes", "questions") : Set.of("intents", "questions"));
            if (root == null) return null;
            JsonObject out = new JsonObject();
            JsonArray intents = new JsonArray();
            for (JsonElement element : list(root, "intents")) {
                JsonObject intent = element.getAsJsonObject();
                only(intent, revise ? REVISE_FIELDS : NEW_FIELDS);
                JsonObject o = new JsonObject();
                for (var entry : intent.entrySet()) {
                    if (entry.getValue().isJsonNull()) continue;   // null 等同未提供
                    switch (entry.getKey()) {
                        case "overrides" -> o.add("overrides", flat(entry.getValue().getAsJsonArray(), "input", "value"));
                        case "data" -> o.add("data", nested(entry.getValue().getAsJsonArray(), Set.of("callId", "input", "value"), e -> e.get("value")));
                        case "slots" -> o.add("slots", nested(entry.getValue().getAsJsonArray(), Set.of("callId", "input", "purpose", "uniqueKey"), e -> {
                            JsonObject spec = new JsonObject();
                            spec.add("purpose", e.get("purpose"));
                            spec.add("uniqueKey", e.get("uniqueKey"));
                            return spec;
                        }));
                        default -> o.add(entry.getKey(), entry.getValue());
                    }
                }
                intents.add(o);
            }
            out.add("intents", intents);
            if (revise) {
                if (root.has("drops") && !root.get("drops").isJsonNull()) out.add("drops", root.getAsJsonArray("drops"));
                if (root.has("changes") && !root.get("changes").isJsonNull()) out.add("changes", root.getAsJsonArray("changes"));
            }
            if (root.has("questions") && !root.get("questions").isJsonNull()) out.add("questions", root.getAsJsonArray("questions"));
            return GSON.toJson(out);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 还原 Operator 响应；toolCalls 非空时只输出工具调用，否则只输出 bindings。不能还原时返回 null。 */
    public static String normalizeMaterialize(String content) {
        try {
            JsonObject root = root(content, Set.of("toolCalls", "bindings"));
            if (root == null) return null;
            JsonObject out = new JsonObject();
            JsonArray tools = root.has("toolCalls") && !root.get("toolCalls").isJsonNull() ? root.getAsJsonArray("toolCalls") : new JsonArray();
            if (!tools.isEmpty()) {
                JsonArray calls = new JsonArray();
                for (JsonElement element : tools) {
                    JsonObject t = element.getAsJsonObject();
                    only(t, Set.of("name", "query", "stage", "function", "arguments"));
                    String name = t.get("name").getAsString();
                    JsonObject arguments = new JsonObject();
                    switch (name) {
                        case "search_functions" -> {
                            arguments.addProperty("query", text(t, "query"));
                            if (!text(t, "stage").isEmpty()) arguments.addProperty("stage", text(t, "stage"));
                        }
                        case "describe_function" -> arguments.addProperty("name", text(t, "function"));
                        case "try_function" -> {
                            arguments.addProperty("name", text(t, "function"));
                            arguments.add("arguments", t.has("arguments") && !t.get("arguments").isJsonNull()
                                    ? flat(t.getAsJsonArray("arguments"), "name", "value") : new JsonObject());
                        }
                        default -> { /* 交给 scenario-runner 记为未登记工具 */ }
                    }
                    JsonObject call = new JsonObject();
                    call.addProperty("name", name);
                    call.add("arguments", arguments);
                    calls.add(call);
                }
                out.add("toolCalls", calls);
                return GSON.toJson(out);
            }
            JsonArray bindings = root.has("bindings") && !root.get("bindings").isJsonNull() ? root.getAsJsonArray("bindings") : new JsonArray();
            out.add("bindings", nested(bindings, Set.of("callId", "input", "source", "value"), e -> {
                JsonObject binding = new JsonObject();
                binding.add("source", e.get("source"));
                binding.add("value", e.get("value"));
                return binding;
            }));
            return GSON.toJson(out);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject root(String content, Set<String> allowed) {
        if (content == null || content.isBlank()) return null;
        String json = content.strip();
        if (json.startsWith("```")) {
            int first = json.indexOf('\n'), last = json.lastIndexOf("```");
            if (!(first > 0 && last > first)) return null;
            json = json.substring(first + 1, last).strip();
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) return null;
        only(parsed.getAsJsonObject(), allowed);
        return parsed.getAsJsonObject();
    }

    /** 出现 schema 以外的字段说明提供方没有遵守 schema（或返回了现有约定的形状），交给调用方原样处理。 */
    private static void only(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("unexpected field " + key);
    }

    private static List<JsonElement> list(JsonObject root, String field) {
        if (!root.has(field) || root.get(field).isJsonNull()) return List.of();
        List<JsonElement> result = new ArrayList<>();
        root.getAsJsonArray(field).forEach(result::add);
        return result;
    }

    private static String text(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : "";
    }

    /** [{key, value}] → {key: value}；重复键后者覆盖前者，与现有读取一致。 */
    private static JsonObject flat(JsonArray entries, String keyField, String valueField) {
        JsonObject result = new JsonObject();
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            only(entry, Set.of(keyField, valueField));
            result.add(entry.get(keyField).getAsString(), entry.get(valueField));
        }
        return result;
    }

    /** [{callId, input, …}] → {callId: {input: value(entry)}}。 */
    private static JsonObject nested(JsonArray entries, Set<String> fields, java.util.function.Function<JsonObject, JsonElement> value) {
        JsonObject result = new JsonObject();
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            only(entry, fields);
            String callId = entry.get("callId").getAsString();
            if (!result.has(callId)) result.add(callId, new JsonObject());
            result.getAsJsonObject(callId).add(entry.get("input").getAsString(), value.apply(entry));
        }
        return result;
    }
}
