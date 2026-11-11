package io.yuan.octopus.scenario.testing;

import io.yuan.octopus.verification.JsonSchemaSubset;

import java.util.List;
import java.util.Map;

import static io.yuan.octopus.scenario.testing.IrValidator.jsonPath;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

/**
 * {@code SCHEMA_CONFORMS}：用 API 契约中的回应 Schema 确定性地校验实际回应（Phase 1C）。
 * <p>
 * 断言格式：{@code {"id": "...", "kind": "SCHEMA_CONFORMS", "path": "$", "expected": null}}
 * <ul>
 *   <li>{@code expected == null}：使用该步骤 {@code STATUS_EQUALS} 对应状态码在契约中的回应 Schema
 *       （IR 中的 Operation 快照），此时 {@code path} 必须为 {@code $}（整个回应体）。</li>
 *   <li>{@code expected} 为对象：作为显式 Schema 校验 {@code path} 所指的子文档。</li>
 * </ul>
 * Schema 中存在校验器无法执行的关键字或未解析的 {@code $ref} 时，IR 校验直接拒绝，
 * 避免"看似校验、实际忽略"。运行时由 JMeter 扩展中的 {@link JsonSchemaSubset} 执行，
 * 违规描述只包含路径与原因，不包含回应中的实际值。
 */
public final class ResponseSchemaChecks {

    public static final String KIND = "SCHEMA_CONFORMS";
    public static final String CAPABILITY = "verify.schema";
    public static final String RUNTIME_CLASS_ENTRY = "io/yuan/octopus/verification/JsonSchemaSubset.class";

    private ResponseSchemaChecks() {
    }

    /** IR 校验：解析出实际使用的 Schema 并检查是否可执行。 */
    public static void validate(Assertion assertion, Step step, Operation operation) {
        jsonPath(assertion.path());
        Map<String, Object> schema = schemaFor(assertion, step, operation);
        List<String> unsupported = JsonSchemaSubset.unsupportedKeywords(schema);
        require(unsupported.isEmpty(), "回应 Schema 含有不支持的关键字或未展开的引用（" + step.id() + "/" + assertion.id() + "）: "
                + String.join(", ", unsupported.subList(0, Math.min(5, unsupported.size())))
                + (unsupported.size() > 5 ? " 等 " + unsupported.size() + " 项" : ""));
    }

    /** 断言实际使用的 Schema：显式 expected 优先，否则取契约中与预期状态码对应的回应 Schema。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> schemaFor(Assertion assertion, Step step, Operation operation) {
        if (assertion.expected() != null) {
            require(assertion.expected() instanceof Map, "SCHEMA_CONFORMS 的 expected 必须是 Schema 对象或留空");
            return (Map<String, Object>) assertion.expected();
        }
        require("$".equals(assertion.path()), "使用契约 Schema 时 path 必须为 $（整个回应体）；校验子字段请提供显式 Schema");
        Integer status = step.assertions().stream()
                .filter(a -> "STATUS_EQUALS".equals(a.kind()) && a.expected() instanceof Number)
                .map(a -> ((Number) a.expected()).intValue())
                .findFirst().orElse(null);
        require(status != null, "SCHEMA_CONFORMS 需要同一步骤的 STATUS_EQUALS 以确定契约中的回应定义");
        Map<String, Object> responses = operation.responseSchema();
        Object schema = responses == null ? null : responses.get(String.valueOf(status));
        if (schema == null && responses != null) {
            // OpenAPI 允许 2XX / default 这类范围定义
            schema = responses.getOrDefault((status / 100) + "XX", responses.get("default"));
        }
        require(schema instanceof Map, "契约中没有状态码 " + status + " 的回应 Schema：" + operation.ref());
        require(!"null".equals(((Map<?, ?>) schema).get("type")), "状态码 " + status + " 的回应没有 JSON 内容，不能做 Schema 校验");
        return (Map<String, Object>) schema;
    }
}
