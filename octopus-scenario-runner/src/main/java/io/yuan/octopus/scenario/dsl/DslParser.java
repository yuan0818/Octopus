package io.yuan.octopus.scenario.dsl;

import io.yuan.octopus.scenario.dsl.DslModel.*;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.*;

/**
 * Deterministic parser of the YAML DSL (FR-PARSE-01/02). It composes the SnakeYAML node tree only — no Java
 * objects are constructed from the document — so every value keeps its line and column, and all problems are
 * reported together instead of stopping at the first.
 *
 * <p>Strict linearity (FR-LIN-02) is enforced at the syntax level: control-flow keys are rejected outright.
 */
public final class DslParser {
    public static final int MAX_BYTES = 1_000_000;
    public static final int MAX_LINE_CHARS = 2_000;
    public static final int MAX_SCENARIOS = 50;
    public static final int MAX_STEPS = 100;
    public static final int MAX_CLEANUP = 30;
    public static final long MAX_WAIT_MILLIS = 60_000;
    private static final Pattern CALL = Pattern.compile("(?:(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+)?(/[^\\s?#]*)");
    private static final Pattern SCENARIO_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "object", "array");
    /** Control-flow vocabulary that has no place in a strictly linear sequence. */
    static final Set<String> FORBIDDEN = Set.of("if", "else", "when", "unless", "loop", "repeat", "times", "foreach", "for_each",
            "for", "while", "until", "retry", "retries", "parallel", "concurrent", "goto", "branch", "switch", "case", "on_failure", "continue_on_error");
    private static final Set<String> DOC_KEYS = Set.of("feature", "project", "environment", "background", "scenarios");
    private static final Set<String> SCENARIO_KEYS = Set.of("name", "id", "tags", "description", "inputs", "steps", "cleanup");
    private static final Set<String> STEP_KEYS = Set.of("call", "name", "service", "with", "query", "headers", "body", "as", "from",
            "expect", "status", "wait", "poll", "role");

    private DslParser() {}

    public static Document parse(String text) {
        var doc = new Document();
        var d = doc.diagnostics;
        if (text == null || text.isBlank()) {
            d.add(DslExpressions.error("EMPTY", "DSL 内容为空", 1, 1, null));
            return doc;
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            d.add(DslExpressions.error("TOO_LARGE", "DSL 文件超过 1 MB", 1, 1, null));
            return doc;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++)
            if (lines[i].length() > MAX_LINE_CHARS) d.add(DslExpressions.error("LINE_TOO_LONG", "单行超过 " + MAX_LINE_CHARS + " 字符", i + 1, 1, null));
        Node root;
        try {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setCodePointLimit(MAX_BYTES);
            root = new Yaml(new SafeConstructor(options)).compose(new StringReader(text));
        } catch (MarkedYAMLException e) {
            var mark = e.getProblemMark();
            d.add(DslExpressions.error("YAML_SYNTAX", "YAML 语法错误: " + e.getProblem(), mark == null ? 1 : mark.getLine() + 1,
                    mark == null ? 1 : mark.getColumn() + 1, "在 { } 或 [ ] 中使用 ${...} 时请加双引号，例如 { id: \"${order.id}\" }"));
            return doc;
        } catch (YAMLException e) {
            d.add(DslExpressions.error("YAML_SYNTAX", "YAML 无法解析", 1, 1, null));
            return doc;
        }
        if (!(root instanceof MappingNode)) {
            d.add(DslExpressions.error("ROOT", "顶层必须是包含 feature 与 scenarios 的映射", 1, 1, null));
            return doc;
        }
        var map = mapping((MappingNode) root, DOC_KEYS, "文件", d);
        doc.feature = string(map.get("feature"), "feature", d);
        doc.project = string(map.get("project"), "project", d);
        doc.environment = string(map.get("environment"), "environment", d);
        if (doc.feature == null || doc.feature.isBlank()) d.add(DslExpressions.error("FEATURE_MISSING", "缺少 feature", 1, 1, null));
        Node background = map.get("background");
        if (background != null) {
            Node steps = background instanceof MappingNode ? mapping((MappingNode) background, Set.of("steps"), "background", d).get("steps") : background;
            doc.background = steps(steps, "background", d);
        }
        Node scenarios = map.get("scenarios");
        if (!(scenarios instanceof SequenceNode) || ((SequenceNode) scenarios).getValue().isEmpty()) {
            d.add(at(scenarios == null ? root : scenarios, "SCENARIOS_MISSING", "至少需要一个场景（scenarios 列表）", null));
            return doc;
        }
        var list = ((SequenceNode) scenarios).getValue();
        if (list.size() > MAX_SCENARIOS) d.add(at(scenarios, "TOO_MANY_SCENARIOS", "单文件最多 " + MAX_SCENARIOS + " 个场景", null));
        for (Node node : list) {
            var s = scenario(node, d);
            if (s != null) doc.scenarios.add(s);
        }
        DslChecker.check(doc);
        return doc;
    }

    private static Scenario scenario(Node node, List<Diagnostic> d) {
        if (!(node instanceof MappingNode)) {
            d.add(at(node, "SCENARIO_SYNTAX", "场景必须是映射（name、steps …）", null));
            return null;
        }
        var map = mapping((MappingNode) node, SCENARIO_KEYS, "场景", d);
        var s = new Scenario();
        s.line = line(node);
        s.name = string(map.get("name"), "name", d);
        if (s.name == null || s.name.isBlank()) d.add(at(node, "SCENARIO_NAME", "场景缺少 name", null));
        s.id = string(map.get("id"), "id", d);
        if (s.id != null && !SCENARIO_ID.matcher(s.id).matches())
            d.add(at(map.get("id"), "SCENARIO_ID", "场景 id 只能包含小写字母、数字和短横线", null));
        s.description = string(map.get("description"), "description", d);
        Node tags = map.get("tags");
        if (tags instanceof SequenceNode) {
            for (Node t : ((SequenceNode) tags).getValue()) {
                String tag = string(t, "tags", d);
                if (tag == null || !TAG.matcher(tag).matches()) d.add(at(t, "TAG", "标签只能包含字母、数字、_ 与 -", null));
                else s.tags.add(tag);
            }
        } else if (tags != null) d.add(at(tags, "TAG", "tags 必须是列表", null));
        Node inputs = map.get("inputs");
        if (inputs instanceof MappingNode) {
            for (NodeTuple t : ((MappingNode) inputs).getValue()) {
                String name = key(t), type = string(t.getValueNode(), "inputs", d);
                if (name == null || !DslExpressions.NAME.matcher(name).matches() || type == null || !TYPES.contains(type))
                    d.add(at(t.getKeyNode(), "INPUT", "inputs 写作 名称: 类型（string / integer / number / boolean / object / array）", null));
                else s.inputs.put(name, type);
            }
        } else if (inputs != null) d.add(at(inputs, "INPUT", "inputs 必须是映射", null));
        s.steps = steps(map.get("steps"), "steps", d);
        if (s.steps.isEmpty()) d.add(at(node, "STEPS_MISSING", "场景至少需要一个步骤", null));
        if (s.steps.size() > MAX_STEPS) d.add(at(map.get("steps"), "TOO_MANY_STEPS", "单场景最多 " + MAX_STEPS + " 个步骤", null));
        if (map.containsKey("cleanup")) {
            s.cleanup = steps(map.get("cleanup"), "cleanup", d);
            if (s.cleanup.size() > MAX_CLEANUP) d.add(at(map.get("cleanup"), "TOO_MANY_CLEANUP", "最多 " + MAX_CLEANUP + " 个清理调用", null));
        }
        return s;
    }

    private static List<Step> steps(Node node, String where, List<Diagnostic> d) {
        var result = new ArrayList<Step>();
        if (node == null) return result;
        if (!(node instanceof SequenceNode)) {
            d.add(at(node, "STEPS_SYNTAX", where + " 必须是步骤列表", null));
            return result;
        }
        for (Node n : ((SequenceNode) node).getValue()) {
            var step = step(n, d);
            if (step != null) result.add(step);
        }
        return result;
    }

    private static Step step(Node node, List<Diagnostic> d) {
        var step = new Step();
        step.line = line(node);
        step.column = column(node);
        if (node instanceof ScalarNode) {
            step.shorthand = true;
            if (!call(((ScalarNode) node).getValue(), step, node, d)) return null;
            return step;
        }
        if (!(node instanceof MappingNode)) {
            d.add(at(node, "STEP_SYNTAX", "步骤写作 “METHOD /path” 或包含 call 的映射", null));
            return null;
        }
        var map = mapping((MappingNode) node, STEP_KEYS, "步骤", d);
        String call = string(map.get("call"), "call", d);
        if (call == null) {
            d.add(at(node, "CALL_MISSING", "步骤缺少 call（要调用的 API 路径）", "例如 call: POST /orders"));
            return null;
        }
        if (!call(call, step, map.get("call"), d)) return null;
        step.name = string(map.get("name"), "name", d);
        step.service = string(map.get("service"), "service", d);
        step.alias = string(map.get("as"), "as", d);
        if (step.alias != null && !DslExpressions.ALIAS.matcher(step.alias).matches())
            d.add(at(map.get("as"), "ALIAS", "as 只能包含字母、数字与下划线，且以字母开头", null));
        step.with = values(map.get("with"), "with", d);
        step.query = values(map.get("query"), "query", d);
        step.headers = values(map.get("headers"), "headers", d);
        if (map.containsKey("body")) step.body = value(map.get("body"), d, 0);
        Node from = map.get("from");
        if (from instanceof SequenceNode) for (Node f : ((SequenceNode) from).getValue()) addFrom(step, string(f, "from", d), f, d);
        else if (from != null) addFrom(step, string(from, "from", d), from, d);
        expects(map.get("expect"), step, d);
        Node status = map.get("status");
        if (status != null) {
            Object v = scalar(status);
            if (v instanceof Long && (Long) v >= 100 && (Long) v <= 599) step.status = ((Long) v).intValue();
            else d.add(at(status, "STATUS", "status 必须是 100–599 的整数", null));
        }
        Node wait = map.get("wait");
        if (wait != null) {
            String text = String.valueOf(scalar(wait));
            long ms = DslExpressions.duration(text);
            if (ms <= 0 || ms > MAX_WAIT_MILLIS) d.add(at(wait, "WAIT", "wait 需要 1 ms–60 s，例如 5s", null));
            else step.waitMillis = ms;
        }
        Node poll = map.get("poll");
        if (poll != null) step.poll = poll(poll, d);
        String role = string(map.get("role"), "role", d);
        if (role != null) {
            String r = role.toUpperCase(Locale.ROOT);
            if (!Set.of("GIVEN", "WHEN", "THEN").contains(r)) d.add(at(map.get("role"), "ROLE", "role 只能是 GIVEN、WHEN 或 THEN", null));
            else step.role = r;
        }
        return step;
    }

    private static void addFrom(Step step, String alias, Node node, List<Diagnostic> d) {
        if (alias == null || !DslExpressions.ALIAS.matcher(alias).matches()) d.add(at(node, "FROM", "from 应列出前序步骤的 as 名称", null));
        else step.from.add(alias);
    }

    private static boolean call(String text, Step step, Node node, List<Diagnostic> d) {
        Matcher m = CALL.matcher(text.trim());
        if (!m.matches()) {
            d.add(at(node, "CALL_SYNTAX", "无法识别的 API: " + text, "写作 METHOD /path，例如 POST /orders/{id}/pay；方法唯一时可省略"));
            return false;
        }
        step.method = m.group(1);
        step.path = m.group(2);
        return true;
    }

    private static PollSpec poll(Node node, List<Diagnostic> d) {
        var p = new PollSpec();
        p.line = line(node);
        if (node instanceof ScalarNode) {
            Object v = scalar(node);
            if (Boolean.TRUE.equals(v)) {
                p.useAuthorization = true;
                return p;
            }
            d.add(at(node, "POLL", "poll 写作 true（使用 API 授权参数）或 {every: 2s, within: 60s}", null));
            return null;
        }
        if (!(node instanceof MappingNode)) {
            d.add(at(node, "POLL", "poll 写作 true 或 {every, within}", null));
            return null;
        }
        var map = mapping((MappingNode) node, Set.of("every", "within"), "poll", d);
        Node every = map.get("every"), within = map.get("within");
        if (every == null || within == null) {
            d.add(at(node, "POLL", "poll 需要同时声明 every 与 within", null));
            return null;
        }
        long e = DslExpressions.duration(String.valueOf(scalar(every))), w = DslExpressions.duration(String.valueOf(scalar(within)));
        if (e <= 0 || w <= 0) {
            d.add(at(node, "POLL", "every / within 需要时长，例如 2s、60s", null));
            return null;
        }
        p.everyMillis = e;
        p.withinMillis = w;
        return p;
    }

    private static void expects(Node node, Step step, List<Diagnostic> d) {
        if (node == null) return;
        if (node instanceof ScalarNode) {
            var e = DslExpressions.expect(((ScalarNode) node).getValue(), line(node), column(node), d);
            if (e != null) step.expects.add(e);
            return;
        }
        if (node instanceof MappingNode) {
            expectMap((MappingNode) node, step, d);
            return;
        }
        for (Node item : ((SequenceNode) node).getValue()) {
            if (item instanceof MappingNode) expectMap((MappingNode) item, step, d);
            else if (item instanceof ScalarNode) {
                var e = DslExpressions.expect(((ScalarNode) item).getValue(), line(item), column(item), d);
                if (e != null) step.expects.add(e);
            } else d.add(at(item, "EXPECT_SYNTAX", "expect 列表项应为断言文本或映射", null));
        }
    }

    /** {@code {status: 409, $.code: ORDER_NOT_PAID}}: typed YAML values are the expected values. */
    private static void expectMap(MappingNode node, Step step, List<Diagnostic> d) {
        for (NodeTuple t : node.getValue()) {
            String key = key(t);
            Node v = t.getValueNode();
            var e = new Expect();
            e.line = line(t.getKeyNode());
            e.column = column(t.getKeyNode());
            if ("status".equals(key)) {
                Object s = scalar(v);
                if (!(s instanceof Long) || (Long) s < 100 || (Long) s > 599) {
                    d.add(at(v, "STATUS", "status 必须是 100–599 的整数", null));
                    continue;
                }
                e.kind = Expect.Kind.STATUS;
                e.expected = s;
            } else if (key != null && key.matches("(db|redis|kafka)(\\[[A-Za-z0-9_.-]{1,64}])?")) {
                var parsed = DslExpressions.expect(key + ": " + scalar(v), e.line, e.column, d);
                if (parsed != null) step.expects.add(parsed);
                continue;
            } else if (key != null && DslExpressions.JSON_PATH.matcher(key).matches()) {
                if (!(v instanceof ScalarNode)) {
                    d.add(at(v, "EXPECT_VALUE", "预期值需要是标量", "对象请拆成多个字段断言"));
                    continue;
                }
                Object expected = scalar(v);
                if (expected instanceof String && ((String) expected).contains("${")) {
                    d.add(at(v, "EXPECT_DYNAMIC_UNSUPPORTED", "预期值暂不支持引用或函数", null));
                    continue;
                }
                e.kind = Expect.Kind.JSON_EQUALS;
                e.path = key;
                e.expected = expected;
            } else {
                d.add(at(t.getKeyNode(), "EXPECT_SYNTAX", "expect 映射的键应为 status、$.路径 或 db/redis/kafka", null));
                continue;
            }
            e.text = key + ": " + (v instanceof ScalarNode ? ((ScalarNode) v).getValue() : "");
            step.expects.add(e);
        }
    }

    private static Map<String, Value> values(Node node, String where, List<Diagnostic> d) {
        var result = new LinkedHashMap<String, Value>();
        if (node == null) return result;
        if (!(node instanceof MappingNode)) {
            d.add(at(node, "VALUES_SYNTAX", where + " 必须是 字段: 值 的映射", null));
            return result;
        }
        for (NodeTuple t : ((MappingNode) node).getValue()) {
            String key = key(t);
            if (key == null || key.isEmpty() || key.length() > 120) {
                d.add(at(t.getKeyNode(), "FIELD_NAME", where + " 的字段名无效", null));
                continue;
            }
            Value v = value(t.getValueNode(), d, 0);
            if (v != null) result.put(key, v);
        }
        return result;
    }

    private static Value value(Node node, List<Diagnostic> d, int depth) {
        if (depth > 20) {
            d.add(at(node, "VALUE_DEPTH", "值嵌套过深", null));
            return null;
        }
        Value v;
        if (node instanceof ScalarNode) {
            Object s = scalar(node);
            v = s instanceof String ? DslExpressions.value((String) s, line(node), column(node), d) : DslExpressions.scalar(String.valueOf(s), line(node), column(node));
            if (s == null) v = new Literal(null);
        } else if (node instanceof MappingNode) {
            var fields = new LinkedHashMap<String, Value>();
            for (NodeTuple t : ((MappingNode) node).getValue()) {
                String key = key(t);
                Value child = value(t.getValueNode(), d, depth + 1);
                if (key != null && child != null) fields.put(key, child);
            }
            v = new ObjectValue(fields);
        } else if (node instanceof SequenceNode) {
            var items = new ArrayList<Value>();
            for (Node n : ((SequenceNode) node).getValue()) {
                Value child = value(n, d, depth + 1);
                if (child != null) items.add(child);
            }
            v = new ArrayValue(items);
        } else return null;
        v.line = line(node);
        v.column = column(node);
        return v;
    }

    /** Typed scalar: only {@code true/false} are booleans and only plain decimal numbers are numbers. */
    static Object scalar(Node node) {
        if (!(node instanceof ScalarNode)) return null;
        var s = (ScalarNode) node;
        String text = s.getValue();
        if (s.getScalarStyle() != DumperStyleHolder.PLAIN) return text;
        if (text.equals("null") || text.equals("~") || text.isEmpty() && Tag.NULL.equals(s.getTag())) return null;
        if (text.equals("true")) return Boolean.TRUE;
        if (text.equals("false")) return Boolean.FALSE;
        if (text.matches("-?(0|[1-9][0-9]{0,17})")) return Long.valueOf(text);
        if (text.matches("-?(0|[1-9][0-9]*)\\.[0-9]+")) return new BigDecimal(text);
        return text;
    }

    /** Holder so the plain-style constant resolves on SnakeYAML 1.x and 2.x alike. */
    private static final class DumperStyleHolder {
        static final org.yaml.snakeyaml.DumperOptions.ScalarStyle PLAIN = org.yaml.snakeyaml.DumperOptions.ScalarStyle.PLAIN;
    }

    private static Map<String, Node> mapping(MappingNode node, Set<String> allowed, String where, List<Diagnostic> d) {
        var result = new LinkedHashMap<String, Node>();
        for (NodeTuple t : node.getValue()) {
            String key = key(t);
            if (key == null) {
                d.add(at(t.getKeyNode(), "KEY", where + " 的键必须是文本", null));
                continue;
            }
            if (FORBIDDEN.contains(key.toLowerCase(Locale.ROOT))) {
                d.add(at(t.getKeyNode(), "LINEAR_FORBIDDEN", "“" + key + "” 会引入分支、循环、重试或并行；测试序列必须严格线性（FR-LIN-02）",
                        "需要不同路径时请写成不同场景；异步结果请用 wait 或对已授权 API 使用 poll"));
                continue;
            }
            if (!allowed.contains(key)) {
                d.add(at(t.getKeyNode(), "UNKNOWN_KEY", where + " 中不支持的键: " + key, "允许: " + String.join("、", new TreeSet<>(allowed))));
                continue;
            }
            if (result.put(key, t.getValueNode()) != null) d.add(at(t.getKeyNode(), "DUPLICATE_KEY", "重复的键: " + key, null));
        }
        return result;
    }

    private static String key(NodeTuple t) {
        return t.getKeyNode() instanceof ScalarNode ? ((ScalarNode) t.getKeyNode()).getValue() : null;
    }

    private static String string(Node node, String field, List<Diagnostic> d) {
        if (node == null) return null;
        if (!(node instanceof ScalarNode)) {
            d.add(at(node, "TYPE", field + " 必须是文本", null));
            return null;
        }
        Object v = scalar(node);
        return v == null ? null : String.valueOf(v);
    }

    static int line(Node node) { return node == null ? 1 : node.getStartMark().getLine() + 1; }

    static int column(Node node) { return node == null ? 1 : node.getStartMark().getColumn() + 1; }

    private static Diagnostic at(Node node, String code, String message, String hint) {
        return DslExpressions.error(code, message, line(node), column(node), hint);
    }
}
