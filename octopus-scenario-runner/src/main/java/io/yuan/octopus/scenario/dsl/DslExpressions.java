package io.yuan.octopus.scenario.dsl;

import io.yuan.octopus.scenario.dsl.DslModel.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic parsing of value expressions ({@code ${...}}) and expectations. No LLM is involved; text that is
 * not a precise expectation is kept verbatim as a NATURAL expectation for later derivation.
 */
public final class DslExpressions {
    public static final Pattern ALIAS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,59}");
    public static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,119}");
    public static final Pattern FIELD_PATH = Pattern.compile("(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+])*");
    public static final Pattern JSON_PATH = Pattern.compile("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+])*");
    private static final Pattern FUNCTION = Pattern.compile("([a-z][A-Za-z0-9_.]{0,80}?)(?:@([0-9][0-9.]{0,20}))?\\((.*)\\)", Pattern.DOTALL);
    private static final Pattern STATUS = Pattern.compile("(?i)status\\s*[:=]?\\s*([1-5][0-9]{2})");
    private static final Pattern DATASTORE = Pattern.compile("(db|redis|kafka)(\\[[A-Za-z0-9_.-]{1,64}])?\\s*:\\s*(.+)", Pattern.DOTALL);
    private static final Pattern EXISTS = Pattern.compile("(\\$[^\\s]*)\\s+exists");
    private static final Pattern COMPARE = Pattern.compile("(\\$[^\\s=!<>]*)\\s*(==|!=|>=|<=|>|<|\\bcontains\\b|\\bin\\b)\\s*(.+)", Pattern.DOTALL);
    private static final Set<String> SCHEMA = Set.of("conforms", "schema", "response conforms to contract", "回应符合契约", "响应符合契约");
    public static final int MAX_FUNCTION_DEPTH = 3;

    private DslExpressions() {}

    /** Parses a YAML string scalar that may contain references, function calls or {@code $${} escapes. */
    public static Value value(String text, int line, int column, List<Diagnostic> diagnostics) {
        var parts = new ArrayList<Object>();
        var literal = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$${", i)) {
                literal.append("${");
                i += 3;
                continue;
            }
            if (text.startsWith("${", i)) {
                int end = closing(text, i + 2);
                if (end < 0) {
                    diagnostics.add(error("EXPR_UNTERMINATED", "表达式缺少右括号 }", line, column, "字面量中的 ${ 请写作 $${"));
                    return lit(text, line, column);
                }
                if (literal.length() > 0) parts.add(literal.toString());
                literal.setLength(0);
                Value inner = inner(text.substring(i + 2, end).trim(), line, column, diagnostics, 0);
                if (inner == null) return lit(text, line, column);
                parts.add(inner);
                i = end + 1;
                continue;
            }
            literal.append(text.charAt(i++));
        }
        if (literal.length() > 0) parts.add(literal.toString());
        if (parts.isEmpty()) return lit("", line, column);
        if (parts.size() == 1 && parts.get(0) instanceof String) return lit(parts.get(0), line, column);
        if (parts.size() == 1) return (Value) parts.get(0);
        var v = new Interpolation(parts);
        v.line = line;
        v.column = column;
        return v;
    }

    /** Index of the brace closing a {@code ${} that starts before {@code from}, honouring quotes and nesting. */
    private static int closing(String text, int from) {
        int depth = 1;
        boolean quoted = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '\\') i++;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static Value inner(String body, int line, int column, List<Diagnostic> diagnostics, int depth) {
        if (body.startsWith("fn.")) return call(body.substring(3), line, column, diagnostics, depth);
        for (var kind : List.of(Ref.Kind.ENV, Ref.Kind.SECRET, Ref.Kind.INPUT)) {
            String prefix = kind.name().toLowerCase(Locale.ROOT) + ".";
            if (body.startsWith(prefix)) {
                String name = body.substring(prefix.length());
                if (!NAME.matcher(name).matches()) {
                    diagnostics.add(error("REF_INVALID", "引用名称无效: ${" + body + "}", line, column, null));
                    return null;
                }
                return ref(kind, name, "", line, column);
            }
        }
        int split = 0;
        while (split < body.length() && (Character.isLetterOrDigit(body.charAt(split)) || body.charAt(split) == '_')) split++;
        String alias = body.substring(0, split), path = body.substring(split);
        if (!ALIAS.matcher(alias).matches() || !FIELD_PATH.matcher(path).matches()) {
            diagnostics.add(error("REF_INVALID", "引用无效: ${" + body + "}", line, column,
                    "可用形式：${步骤别名.字段}、${env.名称}、${secret.名称}、${input.名称}、${fn.函数(参数)}"));
            return null;
        }
        return ref(Ref.Kind.STEP, alias, path, line, column);
    }

    private static Value call(String body, int line, int column, List<Diagnostic> diagnostics, int depth) {
        if (depth >= MAX_FUNCTION_DEPTH) {
            diagnostics.add(error("FN_TOO_DEEP", "函数嵌套不能超过 " + MAX_FUNCTION_DEPTH + " 层", line, column, null));
            return null;
        }
        Matcher m = FUNCTION.matcher(body);
        if (!m.matches()) {
            diagnostics.add(error("FN_SYNTAX", "函数调用语法无效: ${fn." + body + "}", line, column, "形如 ${fn.digest(\"SHA-256\", ${order.id})}"));
            return null;
        }
        var args = new ArrayList<Value>();
        String text = m.group(3).trim();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= text.length()) break;
            int start = i;
            char c = text.charAt(i);
            Value arg;
            if (c == '"') {
                int end = i + 1;
                var s = new StringBuilder();
                boolean closed = false;
                while (end < text.length()) {
                    char d = text.charAt(end);
                    if (d == '\\' && end + 1 < text.length()) { s.append(text.charAt(end + 1)); end += 2; continue; }
                    if (d == '"') { closed = true; break; }
                    s.append(d);
                    end++;
                }
                if (!closed) {
                    diagnostics.add(error("FN_SYNTAX", "函数参数字符串未结束", line, column, null));
                    return null;
                }
                arg = lit(s.toString(), line, column);
                i = end + 1;
            } else if (text.startsWith("${", i)) {
                int end = closing(text, i + 2);
                if (end < 0) {
                    diagnostics.add(error("FN_SYNTAX", "函数参数中的表达式未结束", line, column, null));
                    return null;
                }
                arg = inner(text.substring(i + 2, end).trim(), line, column, diagnostics, depth + 1);
                if (arg == null) return null;
                i = end + 1;
            } else {
                int end = i;
                while (end < text.length() && text.charAt(end) != ',') end++;
                arg = scalar(text.substring(start, end).trim(), line, column);
                i = end;
            }
            args.add(arg);
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i < text.length()) {
                if (text.charAt(i) != ',') {
                    diagnostics.add(error("FN_SYNTAX", "函数参数之间需要逗号", line, column, null));
                    return null;
                }
                i++;
            }
        }
        var v = new Call(m.group(1), m.group(2), args);
        v.line = line;
        v.column = column;
        return v;
    }

    /** Unquoted literal: integer, decimal, true/false/null, or text. */
    public static Value scalar(String text, int line, int column) {
        return lit(literal(text), line, column);
    }

    public static Object literal(String text) {
        String t = text.trim();
        if (t.equals("true")) return Boolean.TRUE;
        if (t.equals("false")) return Boolean.FALSE;
        if (t.equals("null")) return null;
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return unquote(t.substring(1, t.length() - 1));
        if (t.matches("-?(0|[1-9][0-9]{0,17})")) return Long.valueOf(t);
        if (t.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")) return new BigDecimal(t);
        return t;
    }

    private static String unquote(String s) {
        var out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else out.append(c);
        }
        return out.toString();
    }

    /** Parses one expectation written as text. */
    public static Expect expect(String raw, int line, int column, List<Diagnostic> diagnostics) {
        String text = raw.trim();
        var e = new Expect();
        e.line = line;
        e.column = column;
        e.text = text;
        Matcher m;
        if ((m = STATUS.matcher(text)).matches()) {
            e.kind = Expect.Kind.STATUS;
            e.expected = Long.valueOf(m.group(1));
            return e;
        }
        if (SCHEMA.contains(text.toLowerCase(Locale.ROOT))) {
            e.kind = Expect.Kind.SCHEMA;
            return e;
        }
        if ((m = DATASTORE.matcher(text)).matches()) {
            e.kind = Expect.Kind.DATASTORE;
            e.target = m.group(1);
            diagnostics.add(new Diagnostic("WARNING", "EXPECT_NEEDS_DERIVATION", "数据层验证需要推导为只读校验计划（下一增量提供）；当前草稿将标记为待补充",
                    line, column, "也可以在画布中使用数据验证编辑器手动配置"));
            return e;
        }
        if ((m = EXISTS.matcher(text)).matches()) {
            if (!JSON_PATH.matcher(m.group(1)).matches()) {
                diagnostics.add(error("JSON_PATH", "JSON 路径无效: " + m.group(1), line, column, "使用 $.字段 与 [下标]"));
                return null;
            }
            e.kind = Expect.Kind.JSON_EXISTS;
            e.path = m.group(1);
            return e;
        }
        if ((m = COMPARE.matcher(text)).matches()) {
            String path = m.group(1), operator = m.group(2), rhs = m.group(3).trim();
            if (!JSON_PATH.matcher(path).matches()) {
                diagnostics.add(error("JSON_PATH", "JSON 路径无效: " + path, line, column, "使用 $.字段 与 [下标]"));
                return null;
            }
            if (!operator.equals("==")) {
                diagnostics.add(error("EXPECT_OPERATOR_UNSUPPORTED", "比较运算 " + operator + " 将在后续版本提供（FR-DEP-01）", line, column, "当前支持 == 与 exists"));
                return null;
            }
            if (rhs.contains("${")) {
                diagnostics.add(error("EXPECT_DYNAMIC_UNSUPPORTED", "预期值暂不支持引用或函数（跨步骤比较与 EXPECTED_VALUE 函数将在后续版本提供）", line, column, null));
                return null;
            }
            e.kind = Expect.Kind.JSON_EQUALS;
            e.path = path;
            e.expected = literal(rhs);
            return e;
        }
        if (text.startsWith("$")) {
            diagnostics.add(error("EXPECT_SYNTAX", "无法识别的断言: " + text, line, column, "形如 $.state == \"PAID\" 或 $.id exists"));
            return null;
        }
        e.kind = Expect.Kind.NATURAL;
        diagnostics.add(new Diagnostic("WARNING", "EXPECT_NEEDS_DERIVATION", "自然语言验证需要推导为精确断言（下一增量提供）；当前草稿将标记为待补充",
                line, column, "建议直接写精确断言，例如 $.state == \"REFUND_PENDING\""));
        return e;
    }

    /** {@code 5s}, {@code 500ms}, {@code 1m} or a number of milliseconds. Returns -1 when invalid. */
    public static long duration(String text) {
        Matcher m = Pattern.compile("([0-9]{1,7})\\s*(ms|s|m)?").matcher(text.trim());
        if (!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "ms" : m.group(2);
        return unit.equals("ms") ? n : unit.equals("s") ? n * 1000 : n * 60_000;
    }

    public static String duration(long millis) {
        if (millis % 60_000 == 0 && millis >= 60_000) return (millis / 60_000) + "m";
        if (millis % 1000 == 0) return (millis / 1000) + "s";
        return millis + "ms";
    }

    private static Literal lit(Object value, int line, int column) {
        var l = new Literal(value);
        l.line = line;
        l.column = column;
        return l;
    }

    private static Ref ref(Ref.Kind kind, String name, String path, int line, int column) {
        var r = new Ref(kind, name, path);
        r.line = line;
        r.column = column;
        return r;
    }

    static Diagnostic error(String code, String message, int line, int column, String hint) {
        return new Diagnostic("ERROR", code, message, line, column, hint);
    }
}
