package io.yuan.octopus.dto.response;

public record FieldDetailDTO(
    Long id,
    String fieldName,
    String fieldType,
    String modifiers
) {}
