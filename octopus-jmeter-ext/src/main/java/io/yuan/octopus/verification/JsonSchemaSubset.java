package io.yuan.octopus.verification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 回应契约校验（{@code SCHEMA_CONFORMS} Oracle）使用的 JSON Schema 子集校验器。
 * <p>
 * 覆盖 OpenAPI 3.0/3.1 响应 Schema 中常用的校验关键字；在 JMeter 子进程中由固定模板
 * {@code assert.groovy} 调用，也在场景服务的 IR 校验阶段用于检查 Schema 是否可用。
 * 确定性执行，不涉及模型。
 * <p>
 * 支持：type（含数组形式与 "null"）、nullable、enum、const、properties、required、
 * additionalProperties（布尔或 Schema）、items、minItems/maxItems/uniqueItems、
 * minLength/maxLength/pattern、minimum/maximum、exclusiveMinimum/exclusiveMaximum
 * （3.0 布尔形式与 3.1 数值形式）、multipleOf、allOf/anyOf/oneOf/not。
 * <p>
 * 注释类关键字（description、format、example 等）不参与判定；其他未识别的校验关键字
 * 由 {@link #unsupportedKeywords} 列出，IR 校验会拒绝，避免"看似校验、实际忽略"。
 * 未解析的 {@code $ref} 视为不支持（导入阶段应已展开本地引用）。
 * <p>
 * 刻意保持 Java 11 语法，便于在 JMeter 运行时和各类工具链中直接加载。
 */
public final class JsonSchemaSubset {

    /** 单次校验最多报告的违规条数，避免超大响应生成超大报告。 */
    public static final int MAX_VIOLATIONS = 50;
    private static final int MAX_DEPTH = 64;

    private static final Set<String> VALIDATION_KEYWORDS = new HashSet<>(Arrays.asList(
            "type", "nullable", "enum", "const", "properties", "required", "additionalProperties",
            "items", "minItems", "maxItems", "uniqueItems", "minLength", "maxLength", "pattern",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "allOf", "anyOf", "oneOf", "not"));

    private static final Set<String> ANNOTATION_KEYWORDS = new HashSet<>(Arrays.asList(
            "title", "description", "format", "example", "examples", "default", "deprecated",
            "readOnly", "writeOnly", "xml", "externalDocs", "discriminator", "$schema", "$id",
            "$comment", "contentMediaType", "contentEncoding"));

    private JsonSchemaSubset() {
    }

    /**
     * 校验 {@code value} 是否符合 {@code schema}。
     *
     * @return 违规列表（JSON 路径 + 原因），空列表表示通过；最多 {@link #MAX_VIOLATIONS} 条。
     *         违规描述不包含响应中的实际值，避免敏感数据进入 JTL / 报告。
     */
    public static List<String> violations(Object schema, Object value) {
        List<String> out = new ArrayList<>();
        if (!(schema instanceof Map)) {
            out.add("$: schema must be an object");
            return out;
        }
        validate(asMap(schema), value, "$", out, 0);
        return out.size() > MAX_VIOLATIONS ? new ArrayList<>(out.subList(0, MAX_VIOLATIONS)) : out;
    }

    /** 便捷方法：是否完全符合。 */
    public static boolean conforms(Object schema, Object value) {
        return violations(schema, value).isEmpty();
    }

    /**
     * 列出 Schema 中本校验器无法执行的关键字（含未解析的 {@code $ref}），用于 IR 校验阶段拒绝。
     * 返回值为去重后的 "路径:关键字" 列表。
     */
    public static List<String> unsupportedKeywords(Object schema) {
        Set<String> out = new LinkedHashSet<>();
        if (!(schema instanceof Map)) {
            out.add("$:<not-an-object>");
        } else {
            scan(asMap(schema), "$", out, 0);
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ validation

    private static void validate(Map<String, Object> schema, Object value, String path, List<String> out, int depth) {
        if (out.size() > MAX_VIOLATIONS) return;
        if (depth > MAX_DEPTH) {
            out.add(path + ": schema nesting too deep");
            return;
        }
        if (schema.containsKey("$ref")) {
            out.add(path + ": unresolved $ref");
            return;
        }

        if (value == null) {
            if (Boolean.TRUE.equals(schema.get("nullable")) || typeAllows(schema.get("type"), "null")
                    || (!schema.containsKey("type") && !hasCombinators(schema))) {
                checkEnumConst(schema, null, path, out);
                return;
            }
            if (hasCombinators(schema) && !schema.containsKey("type")) {
                combinators(schema, null, path, out, depth);
                return;
            }
            out.add(path + ": null is not allowed");
            return;
        }

        Object type = schema.get("type");
        if (type != null && !typeMatches(type, value)) {
            out.add(path + ": expected " + describe(type) + " but was " + jsonType(value));
            return;
        }

        checkEnumConst(schema, value, path, out);

        if (value instanceof String) {
            String s = (String) value;
            int length = s.codePointCount(0, s.length());
            Number min = number(schema.get("minLength"));
            Number max = number(schema.get("maxLength"));
            if (min != null && length < min.longValue()) out.add(path + ": shorter than minLength " + min);
            if (max != null && length > max.longValue()) out.add(path + ": longer than maxLength " + max);
            Object pattern = schema.get("pattern");
            if (pattern instanceof String) {
                try {
                    if (!Pattern.compile((String) pattern).matcher(s).find()) out.add(path + ": does not match pattern");
                } catch (PatternSyntaxException e) {
                    out.add(path + ": invalid pattern in schema");
                }
            }
        }

        if (value instanceof Number) {
            BigDecimal v = decimal(value);
            BigDecimal min = decimal(schema.get("minimum"));
            BigDecimal max = decimal(schema.get("maximum"));
            Object exMin = schema.get("exclusiveMinimum");
            Object exMax = schema.get("exclusiveMaximum");
            if (min != null) {
                boolean exclusive = Boolean.TRUE.equals(exMin);
                if (exclusive ? v.compareTo(min) <= 0 : v.compareTo(min) < 0) out.add(path + ": below minimum " + min.toPlainString());
            }
            if (max != null) {
                boolean exclusive = Boolean.TRUE.equals(exMax);
                if (exclusive ? v.compareTo(max) >= 0 : v.compareTo(max) > 0) out.add(path + ": above maximum " + max.toPlainString());
            }
            if (exMin instanceof Number && v.compareTo(decimal(exMin)) <= 0) out.add(path + ": not above exclusiveMinimum " + decimal(exMin).toPlainString());
            if (exMax instanceof Number && v.compareTo(decimal(exMax)) >= 0) out.add(path + ": not below exclusiveMaximum " + decimal(exMax).toPlainString());
            BigDecimal multipleOf = decimal(schema.get("multipleOf"));
            if (multipleOf != null && multipleOf.signum() > 0 && v.remainder(multipleOf).signum() != 0) {
                out.add(path + ": not a multiple of " + multipleOf.toPlainString());
            }
        }

        if (value instanceof Map) {
            Map<String, Object> object = asMap(value);
            Object required = schema.get("required");
            if (required instanceof List) {
                for (Object name : (List<?>) required) {
                    if (!object.containsKey(String.valueOf(name))) out.add(path + "." + name + ": required property missing");
                }
            }
            Map<String, Object> properties = schema.get("properties") instanceof Map ? asMap(schema.get("properties")) : Collections.<String, Object>emptyMap();
            Object additional = schema.get("additionalProperties");
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                String childPath = path + "." + entry.getKey();
                Object propertySchema = properties.get(entry.getKey());
                if (propertySchema instanceof Map) {
                    validate(asMap(propertySchema), entry.getValue(), childPath, out, depth + 1);
                } else if (Boolean.FALSE.equals(additional)) {
                    out.add(childPath + ": additional property not allowed");
                } else if (additional instanceof Map) {
                    validate(asMap(additional), entry.getValue(), childPath, out, depth + 1);
                }
            }
        }

        if (value instanceof List) {
            List<?> array = (List<?>) value;
            Number min = number(schema.get("minItems"));
            Number max = number(schema.get("maxItems"));
            if (min != null && array.size() < min.longValue()) out.add(path + ": fewer than minItems " + min);
            if (max != null && array.size() > max.longValue()) out.add(path + ": more than maxItems " + max);
            if (Boolean.TRUE.equals(schema.get("uniqueItems")) && !unique(array)) out.add(path + ": items are not unique");
            Object items = schema.get("items");
            if (items instanceof Map) {
                for (int i = 0; i < array.size() && out.size() <= MAX_VIOLATIONS; i++) {
                    validate(asMap(items), array.get(i), path + "[" + i + "]", out, depth + 1);
                }
            }
        }

        combinators(schema, value, path, out, depth);
    }

    private static void combinators(Map<String, Object> schema, Object value, String path, List<String> out, int depth) {
        Object allOf = schema.get("allOf");
        if (allOf instanceof List) {
            for (Object sub : (List<?>) allOf) {
                if (sub instanceof Map) validate(asMap(sub), value, path, out, depth + 1);
            }
        }
        Object anyOf = schema.get("anyOf");
        if (anyOf instanceof List && countMatches((List<?>) anyOf, value, depth) == 0) {
            out.add(path + ": does not match any schema in anyOf");
        }
        Object oneOf = schema.get("oneOf");
        if (oneOf instanceof List) {
            int matches = countMatches((List<?>) oneOf, value, depth);
            if (matches != 1) out.add(path + ": matches " + matches + " schemas in oneOf (expected exactly 1)");
        }
        Object not = schema.get("not");
        if (not instanceof Map) {
            List<String> sub = new ArrayList<>();
            validate(asMap(not), value, path, sub, depth + 1);
            if (sub.isEmpty()) out.add(path + ": must not match schema in not");
        }
    }

    private static int countMatches(List<?> schemas, Object value, int depth) {
        int matches = 0;
        for (Object sub : schemas) {
            if (!(sub instanceof Map)) continue;
            List<String> probe = new ArrayList<>();
            validate(asMap(sub), value, "$", probe, depth + 1);
            if (probe.isEmpty()) matches++;
        }
        return matches;
    }

    private static void checkEnumConst(Map<String, Object> schema, Object value, String path, List<String> out) {
        Object values = schema.get("enum");
        if (values instanceof List) {
            boolean found = false;
            for (Object candidate : (List<?>) values) {
                if (jsonEquals(candidate, value)) { found = true; break; }
            }
            if (!found) out.add(path + ": value not in enum");
        }
        if (schema.containsKey("const") && !jsonEquals(schema.get("const"), value)) out.add(path + ": value does not equal const");
    }

    // ------------------------------------------------------------------ keyword scan

    private static void scan(Map<String, Object> schema, String path, Set<String> out, int depth) {
        if (depth > MAX_DEPTH) {
            out.add(path + ":<too-deep>");
            return;
        }
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("x-")) continue;                 // OpenAPI 扩展字段
            if ("$ref".equals(key)) { out.add(path + ":$ref"); continue; }
            if (!VALIDATION_KEYWORDS.contains(key) && !ANNOTATION_KEYWORDS.contains(key)) out.add(path + ":" + key);
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map) {
            for (Map.Entry<String, Object> entry : asMap(properties).entrySet()) {
                if (entry.getValue() instanceof Map) scan(asMap(entry.getValue()), path + "." + entry.getKey(), out, depth + 1);
            }
        }
        for (String key : new String[]{"items", "additionalProperties", "not"}) {
            Object sub = schema.get(key);
            if (sub instanceof Map) scan(asMap(sub), path + "/" + key, out, depth + 1);
        }
        for (String key : new String[]{"allOf", "anyOf", "oneOf"}) {
            Object list = schema.get(key);
            if (list instanceof List) {
                int i = 0;
                for (Object sub : (List<?>) list) {
                    if (sub instanceof Map) scan(asMap(sub), path + "/" + key + "[" + i + "]", out, depth + 1);
                    i++;
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasCombinators(Map<String, Object> schema) {
        return schema.containsKey("allOf") || schema.containsKey("anyOf") || schema.containsKey("oneOf");
    }

    private static boolean typeAllows(Object type, String name) {
        if (type instanceof String) return name.equals(type);
        if (type instanceof List) return ((List<?>) type).contains(name);
        return false;
    }

    private static boolean typeMatches(Object type, Object value) {
        if (type instanceof List) {
            for (Object t : (List<?>) type) {
                if (t instanceof String && single((String) t, value)) return true;
            }
            return false;
        }
        return type instanceof String && single((String) type, value);
    }

    private static boolean single(String type, Object value) {
        switch (type) {
            case "string": return value instanceof String;
            case "boolean": return value instanceof Boolean;
            case "object": return value instanceof Map;
            case "array": return value instanceof List;
            case "number": return value instanceof Number;
            case "integer": return value instanceof Number && decimal(value).stripTrailingZeros().scale() <= 0;
            case "null": return value == null;
            default: return false;
        }
    }

    private static String jsonType(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Map) return "object";
        if (value instanceof List) return "array";
        if (value instanceof Number) return decimal(value).stripTrailingZeros().scale() <= 0 ? "integer" : "number";
        return "unknown";
    }

    private static String describe(Object type) {
        return type instanceof List ? String.valueOf(type) : String.valueOf(type);
    }

    /** JSON 语义的相等：数值按大小比较（1 与 1.0 相等），其余递归比较。 */
    private static boolean jsonEquals(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return decimal(a).compareTo(decimal(b)) == 0;
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a, y = (Map<?, ?>) b;
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !jsonEquals(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a, y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!jsonEquals(x.get(i), y.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    private static boolean unique(List<?> array) {
        for (int i = 0; i < array.size(); i++) {
            for (int j = i + 1; j < array.size(); j++) {
                if (jsonEquals(array.get(i), array.get(j))) return false;
            }
        }
        return true;
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static BigDecimal decimal(Object value) {
        if (!(value instanceof Number)) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        return new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
