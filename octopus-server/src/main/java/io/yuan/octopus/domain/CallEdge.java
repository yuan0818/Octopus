package io.yuan.octopus.domain;

import io.yuan.octopus.common.CallType;

public record CallEdge(
    MethodNode caller,
    MethodNode callee,
    CallType callType,
    int lineNumber
) {
    /** Sentinel value indicating no source line number is available (e.g. DI injection edges). */
    public static final int NO_LINE = -1;

    /**
     * Factory method for edges without a meaningful source line number
     * (e.g. Spring DI, AOP, event, scheduled bindings).
     */
    public static CallEdge withoutLine(MethodNode caller, MethodNode callee, CallType callType) {
        return new CallEdge(caller, callee, callType, NO_LINE);
    }
}
