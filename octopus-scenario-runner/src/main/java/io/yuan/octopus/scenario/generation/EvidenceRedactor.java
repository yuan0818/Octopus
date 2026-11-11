package io.yuan.octopus.scenario.generation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 探测证据脱敏（OBS-005、设计 8.4）：敏感路径与字段名替换为 ***，已知 secret 值按字面扫描替换，截断到 256 字符。
 * 敏感输出不保留摘要（NFR-010：不记录秘密值的可猜测摘要）。结果只给人看，不进入任何模型上下文（ADR-CG-06）。
 */
public final class EvidenceRedactor {
    private EvidenceRedactor() {}

    public static final String MASK = "***";
    public static final int MAX_CHARS = 256;
    static final Pattern SENSITIVE_NAME = Pattern.compile("(?i).*(token|password|passwd|secret|authorization|cookie|session|credential|api[-_]?key|private).*");

    /**
     * @param sensitivePaths 敏感输出的提取路径（如 $.token）
     * @param secrets        本次探测解析出的全部 secret 值（初始上下文中的敏感变量、secret 环境变量、连接凭据）
     */
    public static List<Map<String, Object>> redact(List<Map<String, Object>> lines, Set<String> sensitivePaths, Collection<String> secrets) {
        List<String> needles = secrets == null ? List.of() : secrets.stream().filter(s -> s != null && s.length() >= 6).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var line : lines == null ? List.<Map<String, Object>>of() : lines) {
            if (line == null || !(line.get("callId") instanceof String callId)) continue;
            var out = new LinkedHashMap<String, Object>();
            out.put("callId", callId);
            out.put("status", scrub(String.valueOf(line.get("status")), needles));
            List<Map<String, Object>> assertions = new ArrayList<>();
            if (line.get("assertions") instanceof List<?> list) for (var item : list) {
                if (!(item instanceof Map<?, ?> a)) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("id", String.valueOf(a.get("id")));
                String path = a.get("path") == null ? null : String.valueOf(a.get("path"));
                row.put("path", path);
                if (Boolean.TRUE.equals(a.get("missing"))) row.put("missing", true);
                else if (a.containsKey("actual")) row.put("actual", sensitive(path, sensitivePaths) ? MASK : value(a.get("actual"), needles));
                assertions.add(row);
            }
            out.put("assertions", assertions);
            List<Map<String, Object>> outputs = new ArrayList<>();
            if (line.get("outputs") instanceof List<?> list) for (var item : list) {
                if (!(item instanceof Map<?, ?> o)) continue;
                var row = new LinkedHashMap<String, Object>();
                String key = String.valueOf(o.get("key"));
                row.put("key", key);
                boolean secret = Boolean.TRUE.equals(o.get("sensitive")) || SENSITIVE_NAME.matcher(key).matches();
                if (Boolean.TRUE.equals(o.get("missing"))) row.put("missing", true);
                else if (!secret && o.get("sha256") instanceof String sha && sha.matches("[0-9a-f]{64}")) row.put("sha256", sha);
                row.put("sensitive", secret);
                outputs.add(row);
            }
            out.put("outputs", outputs);
            result.add(out);
        }
        return result;
    }

    static boolean sensitive(String path, Set<String> sensitivePaths) {
        if (path == null) return false;
        if (sensitivePaths != null && sensitivePaths.contains(normalize(path))) return true;
        for (String segment : path.split("[.\\[\\]$]")) if (!segment.isBlank() && SENSITIVE_NAME.matcher(segment).matches()) return true;
        return false;
    }

    static String normalize(String path) {
        if (path.equals("$.body")) return "$";
        if (path.startsWith("$.body.") || path.startsWith("$.body[")) return "$" + path.substring(6);
        return path;
    }

    static String value(Object raw, List<String> needles) {
        if (raw == null) return null;
        String text = String.valueOf(raw);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) return "[" + text.length() + " chars, binary]";
        text = scrub(text, needles);
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "…" : text;
    }

    static String scrub(String text, List<String> needles) {
        if (text == null) return null;
        for (String needle : needles) if (text.contains(needle)) text = text.replace(needle, MASK);
        return text;
    }
}
