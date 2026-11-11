package io.yuan.octopus.scenario.extension;

import com.google.gson.*;
import java.util.*;

/**
 * FR-FN-20/22/25/27: executes the discovery tool calls a model returns ({@code {"toolCalls":[...]}}) against the
 * unified catalog. The model never receives the whole catalog; it searches, describes and (for PURE entries
 * with literal arguments) tries entries, within fixed budgets. Results are data for the next round only.
 */
public final class FunctionToolLoop {
    public static final int MAX_CALLS = 10;
    public static final int MAX_TRIES = 5;
    public static final int MAX_CALLS_PER_ROUND = 3;
    public static final int MAX_ROUNDS = 6;

    private final FunctionCatalogService catalog;
    private final List<Map<String, Object>> results = new ArrayList<>();
    private int calls;
    private int tries;
    private int rounds;

    public FunctionToolLoop(FunctionCatalogService catalog) {
        this.catalog = catalog;
    }

    /** The tool definitions to put in the model context. */
    public static List<Map<String, Object>> definitions() {
        return FunctionCatalog.metaTools();
    }

    /** Tool calls in a model response, or null when the response is a normal answer. */
    public static List<Map<String, Object>> toolCalls(String content) {
        if (content == null) return null;
        String text = content.strip();
        if (text.startsWith("```")) text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        if (!text.startsWith("{") || !text.contains("\"toolCalls\"")) return null;
        try {
            var root = JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("toolCalls") || root.has("node") || !root.get("toolCalls").isJsonArray()) return null;
            var list = new ArrayList<Map<String, Object>>();
            for (var e : root.getAsJsonArray("toolCalls")) {
                if (!e.isJsonObject()) continue;
                var o = e.getAsJsonObject();
                var call = new LinkedHashMap<String, Object>();
                call.put("name", o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : "");
                call.put("arguments", o.has("arguments") && o.get("arguments").isJsonObject()
                        ? new Gson().fromJson(o.get("arguments"), Map.class) : Map.of());
                list.add(call);
            }
            return list;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean exhausted() {
        return calls >= MAX_CALLS || rounds >= MAX_ROUNDS;
    }

    public int calls() {
        return calls;
    }

    public List<Map<String, Object>> results() {
        return Collections.unmodifiableList(results);
    }

    /** Executes up to {@link #MAX_CALLS_PER_ROUND} calls of one round; over-budget calls get an error result. */
    @SuppressWarnings("unchecked")
    public void execute(List<Map<String, Object>> round) {
        rounds++;
        int executed = 0;
        for (var call : round) {
            String name = String.valueOf(call.get("name"));
            var arguments = call.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
            Object result;
            if (calls >= MAX_CALLS || executed >= MAX_CALLS_PER_ROUND) result = Map.of("error", "已达到工具调用上限，请直接返回补全结果");
            else {
                calls++;
                executed++;
                result = run(name, arguments);
            }
            var record = new LinkedHashMap<String, Object>();
            record.put("name", name);
            record.put("arguments", arguments);
            record.put("result", result);
            results.add(record);
        }
    }

    private Object run(String name, Map<String, Object> arguments) {
        try {
            switch (name) {
                case "search_functions":
                    return catalog.search(String.valueOf(arguments.getOrDefault("query", "")),
                            arguments.get("stage") == null ? null : String.valueOf(arguments.get("stage")), FunctionCatalog.DEFAULT_SEARCH_LIMIT);
                case "describe_function":
                    return catalog.describe(String.valueOf(arguments.get("name")));
                case "try_function": {
                    if (++tries > MAX_TRIES) return Map.of("error", "试算次数已达上限");
                    Object args = arguments.get("arguments");
                    @SuppressWarnings("unchecked") var map = args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
                    return catalog.tryCall(String.valueOf(arguments.get("name")), normalize(map));
                }
                default:
                    return Map.of("error", "未知工具 " + name + "；可用：search_functions、describe_function、try_function");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("error", e.getMessage() == null ? "调用失败" : e.getMessage());
        }
    }

    /** Gson parses all numbers as doubles; integral values become longs so typed checks behave. */
    private static Map<String, Object> normalize(Map<String, Object> args) {
        var result = new LinkedHashMap<String, Object>();
        args.forEach((k, v) -> result.put(k, v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) d.longValue() : v));
        return result;
    }
}
