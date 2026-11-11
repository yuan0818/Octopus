package io.yuan.octopus.scenario.extension;

import io.yuan.octopus.scenario.extension.ExtensionModel.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * The unified function and tool catalog (FR-FN-01/02): platform preparation tools, whitelisted JMeter
 * built-in functions and published uploaded extensions, all described by the same metadata. From one entry
 * it renders search results, full descriptions, LLM tool definitions (JSON Schema) and Markdown documentation,
 * so people and models always see the same contract (FR-EXT-14).
 */
public final class FunctionCatalog {
    public static final int DEFAULT_SEARCH_LIMIT = 10;

    /** One catalog entry, independent of how it is implemented. */
    public static final class Entry {
        public String id;
        public String version;
        /** PLATFORM, JMETER or EXTENSION. */
        public String kind;
        public String title = "";
        public String summary = "";
        public String description = "";
        public String whenToUse = "";
        public String whenNotToUse = "";
        public String category = "general";
        public List<String> tags = new ArrayList<>();
        public String determinism;
        public List<String> stages = new ArrayList<>();
        public List<Param> params = new ArrayList<>();
        /** Output name → definition. Extensions and built-ins have exactly one output, {@code value}. */
        public Map<String, Output> outputs = new LinkedHashMap<>();
        public boolean sensitiveOutput;
        public List<Example> examples = new ArrayList<>();
        public List<ErrorDef> errors = new ArrayList<>();
        public String deprecated = "";
        public List<String> warnings = new ArrayList<>();
        public String owner = "";

        public String key() { return id + "@" + version; }
    }

    /** Search result: enough to decide whether to describe the entry. */
    public static final class Brief {
        public String name;
        public String kind;
        public String title;
        public String summary;
        public String determinism;
        public List<String> stages;
        public boolean deprecated;
    }

    private final List<Entry> entries;

    public FunctionCatalog(List<Entry> entries) {
        var sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::key));
        this.entries = Collections.unmodifiableList(sorted);
    }

    public List<Entry> entries() { return entries; }

    public Optional<Entry> find(String key) {
        if (key == null) return Optional.empty();
        int at = key.lastIndexOf('@');
        if (at <= 0) return Optional.empty();
        return find(key.substring(0, at), key.substring(at + 1));
    }

    public Optional<Entry> find(String id, String version) {
        return entries.stream().filter(e -> e.id.equals(id) && e.version.equals(version)).findFirst();
    }

    /**
     * FR-FN-20 {@code search_functions}: keyword search over names, summaries, tags and usage notes, optionally
     * restricted to a stage. Deterministic ranking; ties are broken by key.
     */
    public List<Brief> search(String query, String stage, int limit) {
        var terms = terms(query);
        var scored = new ArrayList<Map.Entry<Entry, Integer>>();
        for (Entry e : entries) {
            if (stage != null && !stage.isBlank() && !e.stages.contains(stage)) continue;
            int score = terms.isEmpty() ? 1 : score(e, terms);
            if (score > 0) scored.add(Map.entry(e, score));
        }
        scored.sort((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().key().compareTo(b.getKey().key()) : b.getValue() - a.getValue());
        var result = new ArrayList<Brief>();
        for (var s : scored.subList(0, Math.min(scored.size(), Math.max(1, Math.min(limit, 50))))) result.add(brief(s.getKey()));
        return result;
    }

    private static int score(Entry e, List<String> terms) {
        int score = 0;
        String id = e.id.toLowerCase(Locale.ROOT), title = e.title.toLowerCase(Locale.ROOT), summary = e.summary.toLowerCase(Locale.ROOT);
        String usage = (e.whenToUse + " " + e.description + " " + e.category + " " + String.join(" ", e.tags)).toLowerCase(Locale.ROOT);
        for (String t : terms) {
            if (id.contains(t)) score += 5;
            if (title.contains(t)) score += 4;
            if (summary.contains(t)) score += 3;
            if (usage.contains(t)) score += 1;
        }
        return score;
    }

    /** Lower-case words; CJK text is split into overlapping pairs so Chinese queries match partially. */
    static List<String> terms(String query) {
        var result = new ArrayList<String>();
        if (query == null) return result;
        for (String token : query.toLowerCase(Locale.ROOT).split("[\\s,，。;；:：/|()（）\\[\\]{}\"'`]+")) {
            if (token.isBlank()) continue;
            boolean cjk = token.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
            if (cjk && token.length() > 2) for (int i = 0; i + 2 <= token.length(); i++) result.add(token.substring(i, i + 2));
            else result.add(token);
        }
        return result;
    }

    public static Brief brief(Entry e) {
        var b = new Brief();
        b.name = e.key();
        b.kind = e.kind;
        b.title = e.title;
        b.summary = e.summary;
        b.determinism = e.determinism;
        b.stages = e.stages;
        b.deprecated = !e.deprecated.isEmpty();
        return b;
    }

    /** JSON Schema of an entry's arguments, shared by the LLM tool definition and server-side validation. */
    public static Map<String, Object> argumentSchema(Entry e) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (Param p : e.params) {
            var schema = new LinkedHashMap<String, Object>();
            schema.put("type", jsonType(p.type));
            String description = p.description + (p.sensitive ? "（敏感：只能绑定运行时凭据）" : "");
            if (!p.defaultValue.isEmpty()) description += "；缺省 " + p.defaultValue;
            schema.put("description", description);
            if (!p.enumValues.isEmpty()) schema.put("enum", p.enumValues);
            if (!p.pattern.isEmpty()) schema.put("pattern", p.pattern);
            if (!p.format.isEmpty()) schema.put("x-format", p.format);
            if (p.maxLength >= 0) schema.put("maxLength", p.maxLength);
            if (!p.min.isEmpty()) schema.put("minimum", new BigDecimal(p.min));
            if (!p.max.isEmpty()) schema.put("maximum", new BigDecimal(p.max));
            if (p.sensitive) schema.put("x-sensitive", true);
            properties.put(p.name, schema);
            if (p.required) required.add(p.name);
        }
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** FR-FN-20 {@code describe_function}: the full contract, rendered for a model. */
    public static Map<String, Object> describe(Entry e) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", e.key());
        result.put("kind", e.kind);
        result.put("title", e.title);
        result.put("summary", e.summary);
        if (!e.description.isEmpty()) result.put("description", e.description);
        if (!e.whenToUse.isEmpty()) result.put("whenToUse", e.whenToUse);
        if (!e.whenNotToUse.isEmpty()) result.put("whenNotToUse", e.whenNotToUse);
        result.put("determinism", e.determinism);
        result.put("stages", e.stages);
        result.put("arguments", argumentSchema(e));
        var outputs = new LinkedHashMap<String, Object>();
        e.outputs.forEach((name, o) -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("type", jsonType(o.type));
            out.put("description", o.description);
            if (!o.format.isEmpty()) out.put("format", o.format);
            outputs.put(name, out);
        });
        result.put("outputs", outputs);
        if (e.sensitiveOutput) result.put("sensitiveOutput", true);
        var examples = new ArrayList<Map<String, Object>>();
        for (Example x : e.examples) {
            var ex = new LinkedHashMap<String, Object>();
            var args = new LinkedHashMap<String, Object>();
            for (int i = 0; i < x.args.size() && i < e.params.size(); i++) args.put(e.params.get(i).name, x.args.get(i));
            ex.put("arguments", args);
            ex.put("REGEX".equals(x.match) ? "outputPattern" : "output", x.output);
            if (!x.description.isEmpty()) ex.put("description", x.description);
            examples.add(ex);
        }
        if (!examples.isEmpty()) result.put("examples", examples);
        if (!e.errors.isEmpty()) {
            var errors = new LinkedHashMap<String, String>();
            for (ErrorDef d : e.errors) errors.put(d.code, d.description);
            result.put("errors", errors);
        }
        if (!e.deprecated.isEmpty()) result.put("deprecated", e.deprecated);
        if (!e.warnings.isEmpty()) result.put("warnings", e.warnings);
        result.put("usage", "在预处理计划中写作 {\"tool\":\"" + e.id + "\",\"version\":\"" + e.version + "\",\"inputs\":{参数:绑定}}；"
                + ("PURE".equals(e.determinism) ? "可用于预期值。" : "不得用于预期值。"));
        return result;
    }

    /** The meta tools a model uses to discover and try catalog entries (FR-FN-20, FR-FN-25). */
    public static List<Map<String, Object>> metaTools() {
        return List.of(
                tool("search_functions", "按用途关键词检索函数与工具目录，返回名称、用途与确定性类别。先检索，再按需描述。",
                        Map.of("query", Map.of("type", "string", "description", "用途关键词，例如 签名、时间戳、URL 编码"),
                                "stage", Map.of("type", "string", "enum", List.of("REQUEST_VALUE", "REQUEST_TRANSFORM", "RESPONSE_DECODE", "EXPECTED_VALUE"),
                                        "description", "可选：只返回可用于该阶段的条目")), List.of("query")),
                tool("describe_function", "读取一个目录条目的完整契约：参数 JSON Schema、输出、示例、错误码、何时不应使用。",
                        Map.of("name", Map.of("type", "string", "description", "search_functions 返回的 name，形如 id@version")), List.of("name")),
                tool("try_function", "在隔离环境中试算一个 PURE 条目，只允许普通字面量参数，用于核对推导出的值。",
                        Map.of("name", Map.of("type", "string", "description", "id@version"),
                                "arguments", Map.of("type", "object", "description", "参数名到字面量的映射")), List.of("name", "arguments")));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", new TreeMap<>(properties));
        schema.put("required", required);
        schema.put("additionalProperties", false);
        var tool = new LinkedHashMap<String, Object>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("input_schema", schema);
        return tool;
    }

    /** Human documentation page (FR-EXT-14), from the same metadata as {@link #describe}. */
    public static String markdown(Entry e) {
        var md = new StringBuilder();
        md.append("## ").append(e.title.isEmpty() ? e.id : e.title).append(" `").append(e.key()).append("`\n\n");
        if (!e.deprecated.isEmpty()) md.append("> **已弃用**：").append(e.deprecated).append("\n\n");
        md.append(e.summary).append("\n\n");
        if (!e.description.isEmpty()) md.append(e.description).append("\n\n");
        md.append("| 项 | 内容 |\n|---|---|\n");
        md.append("| 类型 | ").append(e.kind).append(" |\n");
        md.append("| 确定性 | ").append(e.determinism).append(" |\n");
        md.append("| 阶段 | ").append(String.join("、", e.stages)).append(" |\n");
        if (!e.whenToUse.isEmpty()) md.append("| 何时使用 | ").append(cell(e.whenToUse)).append(" |\n");
        if (!e.whenNotToUse.isEmpty()) md.append("| 何时不应使用 | ").append(cell(e.whenNotToUse)).append(" |\n");
        if (!e.owner.isEmpty()) md.append("| 所有者 | ").append(cell(e.owner)).append(" |\n");
        md.append("\n### 参数\n\n");
        if (e.params.isEmpty()) md.append("无\n");
        else {
            md.append("| # | 名称 | 类型 | 必填 | 说明 | 约束 |\n|---|---|---|---|---|---|\n");
            for (Param p : e.params) {
                var constraints = new ArrayList<String>();
                if (!p.enumValues.isEmpty()) constraints.add("取值 " + String.join(" / ", p.enumValues));
                if (!p.pattern.isEmpty()) constraints.add("匹配 `" + p.pattern + "`");
                if (!p.min.isEmpty()) constraints.add("≥ " + p.min);
                if (!p.max.isEmpty()) constraints.add("≤ " + p.max);
                if (p.maxLength >= 0) constraints.add("最长 " + p.maxLength);
                if (!p.format.isEmpty()) constraints.add("格式 " + p.format);
                if (!p.defaultValue.isEmpty()) constraints.add("缺省 " + p.defaultValue);
                if (p.sensitive) constraints.add("敏感");
                md.append("| ").append(p.index).append(" | `").append(p.name).append("` | ").append(p.type).append(" | ")
                        .append(p.required ? "是" : "否").append(" | ").append(cell(p.description)).append(" | ")
                        .append(cell(String.join("；", constraints))).append(" |\n");
            }
        }
        md.append("\n### 输出\n\n");
        e.outputs.forEach((name, o) -> md.append("- `").append(name).append("`（").append(o.type).append(o.format.isEmpty() ? "" : "，" + o.format)
                .append("）：").append(o.description).append(e.sensitiveOutput ? "（敏感）" : "").append("\n"));
        if (!e.examples.isEmpty()) {
            md.append("\n### 示例\n\n");
            for (Example x : e.examples)
                md.append("- `(").append(String.join(", ", x.args)).append(")` → ").append("REGEX".equals(x.match) ? "匹配 " : "")
                        .append("`").append(x.output).append("`").append(x.description.isEmpty() ? "" : "：" + x.description).append("\n");
        }
        if (!e.errors.isEmpty()) {
            md.append("\n### 错误码\n\n");
            for (ErrorDef d : e.errors) md.append("- `").append(d.code).append("`：").append(d.description).append("\n");
        }
        if (!e.warnings.isEmpty()) {
            md.append("\n### 风险提示\n\n");
            for (String w : e.warnings) md.append("- ").append(w).append("\n");
        }
        return md.toString();
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    static String jsonType(String type) {
        if (type == null) return "string";
        switch (type.toUpperCase(Locale.ROOT)) {
            case "INTEGER": return "integer";
            case "NUMBER": return "number";
            case "BOOLEAN": return "boolean";
            case "OBJECT": return "object";
            case "ANY": return "any";
            default: return "string";
        }
    }

    /** Converts uploaded-extension metadata into a catalog entry. */
    public static Entry fromTool(Tool t, String deprecated, List<String> warnings) {
        var e = new Entry();
        e.id = t.catalogId();
        e.version = t.version;
        e.kind = "EXTENSION";
        e.title = t.displayName.isEmpty() ? t.name : t.displayName;
        e.summary = t.summary;
        e.description = t.description;
        e.whenToUse = t.whenToUse;
        e.whenNotToUse = t.whenNotToUse;
        e.category = t.category;
        e.tags = t.tags;
        e.determinism = t.determinism;
        e.stages = t.stages;
        e.params = t.params;
        e.outputs.put("value", t.output);
        e.sensitiveOutput = t.sensitiveOutput;
        e.examples = t.examples;
        e.errors = t.errors;
        e.owner = t.owner;
        e.deprecated = deprecated != null ? deprecated : t.deprecated == null ? "" : t.deprecated;
        if (warnings != null) e.warnings = warnings;
        return e;
    }
}
