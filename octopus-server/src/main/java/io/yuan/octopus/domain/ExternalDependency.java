package io.yuan.octopus.domain;

import io.yuan.octopus.common.DependencyType;

public record ExternalDependency(
    String qualifiedName,
    DependencyType type
) {}
