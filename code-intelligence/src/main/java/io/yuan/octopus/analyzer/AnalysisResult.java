package io.yuan.octopus.analyzer;

import java.util.List;

/**
 * Outcome of a full analysis run for one project tree.
 *
 * @param language          the language that was detected and used for analysis
 * @param moduleNames       names of the sub-modules/projects that were analysed
 * @param warnings          non-fatal warnings (e.g. PSI degraded to JavaParser)
 * @param nodeCount         total method nodes stored
 * @param edgeCount         total call edges stored
 */
public record AnalysisResult(
    String language,
    List<String> moduleNames,
    List<String> warnings,
    int nodeCount,
    int edgeCount
) {

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    public static AnalysisResult empty(String language) {
        return new AnalysisResult(language, List.of(), List.of(), 0, 0);
    }
}
