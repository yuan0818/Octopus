package io.yuan.octopus.review.model;

import java.util.Locale;
import java.util.Set;

/**
 * 模型的结构化输出模式（SO-001、SO-002）：不启用（null，存储为 null）、{@code json_object}、{@code json_schema}。
 * Anthropic 接口没有 JSON 模式，只能选择 JSON Schema 或不启用。
 */
public final class StructuredOutputMode {
    public static final String NONE = "none";
    public static final String JSON_OBJECT = "json_object";
    public static final String JSON_SCHEMA = "json_schema";

    private StructuredOutputMode() {}

    /** 保存配置时使用：返回规范值（不启用为 null），不合法时抛出带中文说明的 IllegalArgumentException。 */
    public static String normalize(String apiFormat, String provider, String value) {
        if (value == null || value.isBlank() || NONE.equalsIgnoreCase(value.strip())) return null;
        String mode = value.strip().toLowerCase(Locale.ROOT);
        if (!Set.of(JSON_OBJECT, JSON_SCHEMA).contains(mode))
            throw new IllegalArgumentException("结构化输出只能是 none、json_object 或 json_schema");
        if (JSON_OBJECT.equals(mode) && claudeFormat(apiFormat, provider))
            throw new IllegalArgumentException("Anthropic 接口不支持 JSON 模式（json_object），请选择 JSON Schema 或不启用");
        return mode;
    }

    /** 运行时使用：存量的非法值按不启用处理，而不是让调用失败。 */
    public static String effective(ModelConfig config) {
        if (config == null) return null;
        try { return normalize(config.getApiFormat(), config.getProvider(), config.getStructuredOutput()); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** 与 HttpLLMAdapter 的报文格式判定一致：显式 apiFormat 优先，否则按 provider 推断。 */
    public static boolean claudeFormat(String apiFormat, String provider) {
        if (apiFormat != null && !apiFormat.isBlank()) return "claude".equalsIgnoreCase(apiFormat);
        return "claude".equals(provider);
    }
}
