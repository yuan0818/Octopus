package io.yuan.octopus.extension.sdk;

import java.math.BigDecimal;
import java.util.*;

/** Minimal JSON reader/writer so the SDK has no runtime dependency beyond JMeter itself. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        var parser = new Parser(text);
        parser.space();
        Object value = parser.value(0);
        parser.space();
        if (parser.i != text.length()) throw new IllegalArgumentException("Trailing characters");
        return value;
    }

    public static String write(Object value) {
        var out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) out.append("null");
        else if (value instanceof String) quote((String) value, out);
        else if (value instanceof Boolean) out.append(value);
        else if (value instanceof BigDecimal) out.append(((BigDecimal) value).stripTrailingZeros().toPlainString());
        else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite number");
            out.append(new BigDecimal(value.toString()).stripTrailingZeros().toPlainString());
        } else if (value instanceof Number) out.append(value);
        else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (var e : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Collection) {
            out.append('[');
            boolean first = true;
            for (Object item : (Collection<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        void space() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value(int depth) {
            if (depth > 64) throw new IllegalArgumentException("JSON nested too deeply");
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object(int depth) {
            var result = new LinkedHashMap<String, Object>();
            i++;
            space();
            if (peek('}')) { i++; return result; }
            while (true) {
                space();
                if (!peek('"')) throw new IllegalArgumentException("Expected key");
                String key = string();
                space();
                expect(':');
                space();
                if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate key: " + key);
                result.put(key, value(depth + 1));
                space();
                if (peek(',')) { i++; continue; }
                expect('}');
                return result;
            }
        }

        List<Object> array(int depth) {
            var result = new ArrayList<Object>();
            i++;
            space();
            if (peek(']')) { i++; return result; }
            while (true) {
                space();
                result.add(value(depth + 1));
                space();
                if (peek(',')) { i++; continue; }
                expect(']');
                return result;
            }
        }

        String string() {
            expect('"');
            var out = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("Bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': case '\\': case '/': out.append(e); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("Bad escape");
                    }
                } else if (c < 0x20) throw new IllegalArgumentException("Control character in string");
                else out.append(c);
            }
        }

        BigDecimal number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("Unexpected character at " + start);
            return new BigDecimal(s.substring(start, i));
        }

        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

        void expect(char c) {
            if (!peek(c)) throw new IllegalArgumentException("Expected '" + c + "' at " + i);
            i++;
        }
    }
}
