package io.yuan.octopus.dto.response;

import java.util.List;

/**
 * A method node enriched with the edge metadata (call type and line number)
 * from the CALLS relationship that connects to this method.
 */
public record MethodEdgeDTO(
    String qualifiedClassName,
    String methodName,
    List<String> parameterTypes,
    String returnType,
    String modifiers,
    String signature,
    String callType,
    int lineNumber
) {}
