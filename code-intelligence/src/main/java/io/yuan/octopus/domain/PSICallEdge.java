package io.yuan.octopus.domain;

/**
 * Call edge produced by the IntelliJ PSI analyzer.
 * Uses signature strings to reference caller/callee (the JSON interop format)
 * rather than the in-memory {@link MethodNode} objects used by {@link CallEdge}.
 */
public record PSICallEdge(
    String callerSignature,
    /** Standard call edges (DIRECT/INTERFACE/SUPER/LAMBDA). */
    String calleeSignature,
    String callType,
    int lineNumber,
    String calleeBeanName,
    /**
     * For SPRING_DI edges the PSI plugin emits the injected bean's class name here
     * instead of a full method signature in {@code calleeSignature}.
     */
    String calleeClassName,
    /** Field name of the injected dependency (informational). */
    String calleeFieldName
) {}
