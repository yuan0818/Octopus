package io.yuan.octopus.dto.response;

import java.util.List;

public record MethodNodeDTO(
    String qualifiedClassName,
    String methodName,
    List<String> parameterTypes,
    String returnType,
    String modifiers,
    String signature
) {}
