package io.yuan.octopus.domain;

import java.util.List;

public record ParsedMethod(
    String name,
    String signature,
    List<String> parameterTypes,
    String returnType,
    List<String> modifiers,
    String sourceCode,
    List<MethodCall> methodCalls,
    List<ParsedAnnotation> annotations
) {}
