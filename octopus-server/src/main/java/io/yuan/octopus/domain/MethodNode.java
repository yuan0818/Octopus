package io.yuan.octopus.domain;

import java.util.List;

public record MethodNode(
    String qualifiedClassName,
    String methodName,
    List<String> parameterTypes,
    String returnType,
    String modifiers,
    String signature,
    String sourceFilePath,
    String sourceCode
) {}
