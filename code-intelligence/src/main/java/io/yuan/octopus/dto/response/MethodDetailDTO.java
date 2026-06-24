package io.yuan.octopus.dto.response;

import java.util.List;

public record MethodDetailDTO(
    Long id,
    String methodSignature,
    String methodName,
    List<String> parameterTypes,
    String returnType,
    String modifiers,
    String sourceCode
) {}
