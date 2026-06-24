package io.yuan.octopus.domain;

import java.util.Set;

public record CallGraph(
    Set<MethodNode> nodes,
    Set<CallEdge> edges
) {}
