package io.yuan.octopus.dto.response;

public record ClassSummaryDTO(
    Long id,
    String qualifiedName,
    String simpleName,
    String classType,
    String modifiers
) {}
