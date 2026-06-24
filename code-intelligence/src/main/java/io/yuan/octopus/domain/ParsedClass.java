package io.yuan.octopus.domain;

import io.yuan.octopus.common.ClassType;

import java.util.List;

public record ParsedClass(
    String qualifiedName,
    String simpleName,
    ClassType classType,
    List<String> modifiers,
    String parentClass,
    List<String> interfaces,
    List<ParsedMethod> methods,
    List<ParsedField> fields,
    List<ParsedAnnotation> annotations,
    String sourceFilePath,
    List<ExternalDependency> externalDependencies
) {}
