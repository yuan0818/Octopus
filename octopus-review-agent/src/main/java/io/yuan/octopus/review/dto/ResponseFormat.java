package io.yuan.octopus.review.dto;

import java.util.Map;

/**
 * 原生结构化输出请求（SO-001）：{@code json_object} 只约束为合法 JSON；{@code json_schema} 按固定 schema 约束形状。
 * 为 null 时请求体与未启用结构化输出时完全一致。
 */
public record ResponseFormat(String type, String name, Map<String, Object> schema) {
    public static final String JSON_OBJECT = "json_object";
    public static final String JSON_SCHEMA = "json_schema";

    public static ResponseFormat jsonObject() { return new ResponseFormat(JSON_OBJECT, null, null); }

    public static ResponseFormat jsonSchema(String name, Map<String, Object> schema) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}") || schema == null) throw new IllegalArgumentException("schema 名称或内容无效");
        return new ResponseFormat(JSON_SCHEMA, name, schema);
    }
}
