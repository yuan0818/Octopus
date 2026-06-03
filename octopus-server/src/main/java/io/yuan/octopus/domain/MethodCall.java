package io.yuan.octopus.domain;

import io.yuan.octopus.common.CallType;

import java.util.List;

public record MethodCall(
    String targetClassName,
    String targetMethodName,
    List<String> argumentTypes,
    CallType callType,
    int lineNumber
) {}
