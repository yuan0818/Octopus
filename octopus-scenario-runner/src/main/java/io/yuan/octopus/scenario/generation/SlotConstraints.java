package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.testing.TestingJson.canonical;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 数据取值的契约约束校验（OPER-002）：模型或 Planner 给出的字面量不仅要类型一致，还要满足（VALID）或确实违反（VIOLATING）
 * 该输入在契约中的约束。平台无法验证的格式不会被当作满足，而是标记为需人工确认。
 */
public final class SlotConstraints {
    private SlotConstraints() {}

    public record Check(boolean ok, String problem, boolean unverified) {
        static Check pass() { return new Check(true, null, false); }
        static Check fail(String problem) { return new Check(false, problem, false); }
        static Check unknown(String problem) { return new Check(true, problem, true); }
    }

    static final Set<String> KNOWN_FORMATS = Set.of("date", "date-time", "email", "uuid", "ipv4");
    static final int MAX_PATTERN = 500, MAX_VALUE = 8000;

    /** 取值相对约束的结论；constraints 为空表示没有可校验的约束。 */
    public static Check check(Object value, Map<String, Object> constraints, String purpose) {
        var c = constraints == null ? Map.<String, Object>of() : constraints;
        var violations = violations(value, c);
        boolean unverified = c.get("format") instanceof String f && !KNOWN_FORMATS.contains(f)
                || c.keySet().stream().anyMatch(k -> Set.of("allOf", "anyOf", "oneOf", "not", "$ref").contains(k));
        if ("VIOLATING".equals(purpose)) {
            if (!violations.isEmpty()) return Check.pass();
            return unverified ? Check.unknown("无法确认取值违反了约束") : Check.fail("取值没有违反任何已声明的约束");
        }
        if (!violations.isEmpty()) return Check.fail("取值不满足约束：" + String.join("、", violations));
        return unverified ? Check.unknown("取值的格式或组合约束未经平台验证，需人工确认") : Check.pass();
    }

    static List<String> violations(Object value, Map<String, Object> c) {
        List<String> result = new ArrayList<>();
        if (c.isEmpty()) return result;
        if (c.containsKey("const") && !canonical(c.get("const")).equals(canonical(value))) result.add("const");
        if (!RuleIntentSource.validScalar(value, c)) result.add("范围、长度、枚举或倍数");
        if (value instanceof String s) {
            if (s.length() > MAX_VALUE) result.add("长度");
            if (c.get("pattern") instanceof String p) {
                if (p.length() > MAX_PATTERN) result.add("pattern（过长，无法验证）");
                else try { if (!Pattern.compile(p).matcher(s).find()) result.add("pattern"); }
                catch (PatternSyntaxException e) { result.add("pattern（无法解析）"); }
            }
            if (c.get("format") instanceof String f && KNOWN_FORMATS.contains(f) && !format(f, s)) result.add("format " + f);
        }
        return result;
    }

    static boolean format(String format, String s) {
        try {
            return switch (format) {
                case "date" -> { java.time.LocalDate.parse(s); yield true; }
                case "date-time" -> { java.time.OffsetDateTime.parse(s); yield true; }
                case "email" -> s.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
                case "uuid" -> s.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
                case "ipv4" -> s.matches("((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)");
                default -> false;
            };
        } catch (java.time.format.DateTimeParseException e) { return false; }
    }

    /** 契约中某个输入的约束（x-octopus-parameters）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> of(io.yuan.octopus.scenario.testing.TestingModel.Operation op, String input) {
        Object schema = op == null || op.requestSchema() == null ? null
                : op.requestSchema().get("x-octopus-parameters") instanceof Map<?, ?> map ? map.get(input) : null;
        return schema instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
