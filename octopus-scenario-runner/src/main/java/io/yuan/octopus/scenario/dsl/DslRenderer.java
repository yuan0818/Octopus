package io.yuan.octopus.scenario.dsl;

import io.yuan.octopus.scenario.dsl.DslModel.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * Canonical YAML rendering (FR-RT-02/03): fixed key order, two-space indentation, string data always
 * double-quoted, expressions and expectations single-quoted. Rendering a parsed document and parsing it again
 * yields the same document, so diffs only show real changes.
 */
public final class DslRenderer {
    private DslRenderer() {}

    public static String render(Document doc) {
        var out = new StringBuilder();
        out.append("feature: ").append(dq(doc.feature == null ? "" : doc.feature)).append('\n');
        if (doc.project != null) out.append("project: ").append(dq(doc.project)).append('\n');
        if (doc.environment != null) out.append("environment: ").append(dq(doc.environment)).append('\n');
        if (!doc.background.isEmpty()) {
            out.append("background:\n  steps:\n");
            for (Step s : doc.background) step(s, out, 4);
        }
        out.append("scenarios:\n");
        for (Scenario s : doc.scenarios) {
            out.append("  - name: ").append(dq(s.name == null ? "" : s.name)).append('\n');
            if (s.id != null) out.append("    id: ").append(s.id).append('\n');
            if (!s.tags.isEmpty()) out.append("    tags: [").append(String.join(", ", s.tags)).append("]\n");
            if (s.description != null) out.append("    description: ").append(dq(s.description)).append('\n');
            if (!s.inputs.isEmpty()) {
                out.append("    inputs:\n");
                s.inputs.forEach((k, v) -> out.append("      ").append(key(k)).append(": ").append(v).append('\n'));
            }
            out.append("    steps:\n");
            for (Step step : s.steps) step(step, out, 6);
            if (!s.cleanup.isEmpty()) {
                out.append("    cleanup:\n");
                for (Step step : s.cleanup) step(step, out, 6);
            }
        }
        return out.toString();
    }

    private static void step(Step s, StringBuilder out, int indent) {
        String pad = " ".repeat(indent), inner = " ".repeat(indent + 2);
        String call = (s.method == null ? "" : s.method + " ") + s.path;
        boolean bare = s.name == null && s.service == null && s.with.isEmpty() && s.query.isEmpty() && s.headers.isEmpty() && s.body == null
                && s.alias == null && s.from.isEmpty() && s.expects.isEmpty() && s.status == null && s.waitMillis == 0 && s.poll == null && s.role == null;
        if (bare) {
            out.append(pad).append("- ").append(call).append('\n');
            return;
        }
        out.append(pad).append("- call: ").append(call).append('\n');
        if (s.name != null) out.append(inner).append("name: ").append(dq(s.name)).append('\n');
        if (s.service != null) out.append(inner).append("service: ").append(dq(s.service)).append('\n');
        map("with", s.with, out, indent + 2);
        map("query", s.query, out, indent + 2);
        map("headers", s.headers, out, indent + 2);
        if (s.body != null) {
            out.append(inner).append("body:");
            value(s.body, out, indent + 4);
        }
        if (s.alias != null) out.append(inner).append("as: ").append(s.alias).append('\n');
        if (!s.from.isEmpty()) out.append(inner).append("from: [").append(String.join(", ", s.from)).append("]\n");
        if (s.waitMillis > 0) out.append(inner).append("wait: ").append(DslExpressions.duration(s.waitMillis)).append('\n');
        if (s.poll != null) {
            if (s.poll.useAuthorization) out.append(inner).append("poll: true\n");
            else out.append(inner).append("poll: { every: ").append(DslExpressions.duration(s.poll.everyMillis))
                    .append(", within: ").append(DslExpressions.duration(s.poll.withinMillis)).append(" }\n");
        }
        if (s.status != null) out.append(inner).append("status: ").append(s.status).append('\n');
        if (s.role != null) out.append(inner).append("role: ").append(s.role).append('\n');
        if (!s.expects.isEmpty()) {
            out.append(inner).append("expect:\n");
            for (Expect e : s.expects) out.append(inner).append("  - ").append(sq(expect(e))).append('\n');
        }
    }

    public static String expect(Expect e) {
        switch (e.kind) {
            case STATUS: return "status " + e.expected;
            case JSON_EXISTS: return e.path + " exists";
            case JSON_EQUALS: return e.path + " == " + literal(e.expected);
            case SCHEMA: return "conforms";
            default: return e.text;
        }
    }

    /** Inline literal for an expectation's right-hand side. */
    static String literal(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return json((String) v);
        if (v instanceof BigDecimal) return ((BigDecimal) v).toPlainString();
        return String.valueOf(v);
    }

    private static void map(String name, Map<String, Value> values, StringBuilder out, int indent) {
        if (values.isEmpty()) return;
        String pad = " ".repeat(indent);
        out.append(pad).append(name).append(":\n");
        for (var e : values.entrySet()) {
            out.append(pad).append("  ").append(key(e.getKey())).append(':');
            value(e.getValue(), out, indent + 4);
        }
    }

    /** Appends " scalar\n" or a nested block starting on the next line. */
    private static void value(Value v, StringBuilder out, int indent) {
        String pad = " ".repeat(indent);
        if (v instanceof ObjectValue) {
            var fields = ((ObjectValue) v).fields;
            if (fields.isEmpty()) { out.append(" {}\n"); return; }
            out.append('\n');
            for (var e : fields.entrySet()) {
                out.append(pad).append(key(e.getKey())).append(':');
                value(e.getValue(), out, indent + 2);
            }
        } else if (v instanceof ArrayValue) {
            var items = ((ArrayValue) v).items;
            if (items.isEmpty()) { out.append(" []\n"); return; }
            out.append('\n');
            for (Value item : items) {
                out.append(pad).append("-");
                value(item, out, indent + 2);
            }
        } else out.append(' ').append(scalar(v)).append('\n');
    }

    private static String scalar(Value v) {
        if (v instanceof Literal) {
            Object o = ((Literal) v).value;
            if (o instanceof String) return dq(((String) o).replace("${", "$${"));
            return literal(o);
        }
        return sq(expression(v));
    }

    /** Expression text as written in the DSL. */
    public static String expression(Value v) {
        if (v instanceof Ref) return ((Ref) v).text();
        if (v instanceof Call) {
            var c = (Call) v;
            var args = new StringJoiner(", ");
            for (Value a : c.args) {
                if (a instanceof Literal) {
                    Object o = ((Literal) a).value;
                    args.add(o instanceof String ? json((String) o) : literal(o));
                } else args.add(expression(a));
            }
            return "${fn." + c.function + (c.version == null ? "" : "@" + c.version) + "(" + args + ")}";
        }
        if (v instanceof Interpolation) {
            var sb = new StringBuilder();
            for (Object p : ((Interpolation) v).parts) sb.append(p instanceof String ? ((String) p).replace("${", "$${") : expression((Value) p));
            return sb.toString();
        }
        if (v instanceof Literal) return String.valueOf(((Literal) v).value);
        return "";
    }

    private static String key(String k) {
        return k.matches("[A-Za-z_$][A-Za-z0-9_.$\\[\\]-]*") && !Set.of("true", "false", "null", "yes", "no", "on", "off").contains(k) ? k : dq(k);
    }

    /**
     * Expression or expectation text: plain when YAML would read it back as the same string (block context),
     * otherwise single-quoted (only {@code '} needs escaping), or double-quoted for control characters.
     */
    static String sq(String s) {
        if (plainSafe(s)) return s;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20) return dq(s);
        return "'" + s.replace("'", "''") + "'";
    }

    private static boolean plainSafe(String s) {
        if (s.isEmpty() || s.length() > 200 || Character.isWhitespace(s.charAt(0)) || Character.isWhitespace(s.charAt(s.length() - 1))) return false;
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(s.charAt(0)) >= 0) return false;
        if (s.contains(": ") || s.contains(" #") || s.endsWith(":") || s.indexOf('"') >= 0 || s.indexOf('\'') >= 0) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20) return false;
        // Would be typed as something other than a string.
        return !s.matches("-?[0-9].*|true|false|null|~|yes|no|on|off|True|False|Null|TRUE|FALSE|NULL");
    }

    /** YAML double-quoted scalar using JSON-compatible escapes. */
    static String dq(String s) {
        return json(s);
    }

    static String json(String s) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\t': out.append("\\t"); break;
                case '\r': out.append("\\r"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
