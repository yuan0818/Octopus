package io.yuan.octopus.dto.response;

import java.util.List;

/**
 * Combines both upstream callers and downstream callees for a method call trace,
 * designed for graph visualization with configurable depth.
 */
public record CallTraceDTO(
    MethodNodeDTO anchor,
    List<MethodEdgeDTO> upstream,
    List<MethodEdgeDTO> downstream
) {}
