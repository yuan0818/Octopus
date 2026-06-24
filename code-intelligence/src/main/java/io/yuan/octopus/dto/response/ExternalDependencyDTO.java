package io.yuan.octopus.dto.response;

public record ExternalDependencyDTO(
    String dependencyQualifiedName,
    String dependencyType
) {}
