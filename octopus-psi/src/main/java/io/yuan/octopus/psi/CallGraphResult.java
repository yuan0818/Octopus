package io.yuan.octopus.psi;

import java.util.List;
import java.util.Map;

/**
 * Intermediate result holder produced by {@link CallGraphExtractor}.
 */
public record CallGraphResult(
    List<Map<String, Object>> methods,
    List<Map<String, Object>> edges
) {}
