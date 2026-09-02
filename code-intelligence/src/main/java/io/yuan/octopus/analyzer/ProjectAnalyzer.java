package io.yuan.octopus.analyzer;

import io.yuan.octopus.domain.DiscoveredProject;
import io.yuan.octopus.entity.RepoSnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * Strategy interface for language-specific project analysis.
 *
 * <p>Each implementation handles a particular language ecosystem
 * (e.g. JVM/Maven/Gradle, Python/pip, Node/npm). The
 * {@link AnalysisDispatcher} detects the project language and
 * routes to the matching adapter.
 *
 * <p>Implementations are discovered automatically via Spring's
 * component scanning — just annotate the class with {@code @Service}.
 */
public interface ProjectAnalyzer {

    /**
     * Human-readable language name, e.g. {@code "JVM"}, {@code "Python"}.
     */
    String getLanguage();

    /**
     * Returns {@code true} if the directory at {@code projectRoot}
     * contains a project that this analyzer can handle.
     * <p>
     * Detection should be fast (check for build files, config files, etc.)
     * and must not recurse into sub-directories.
     *
     * @param projectRoot the root directory to probe
     * @return whether this analyzer supports the project
     */
    boolean supports(Path projectRoot);

    /**
     * Priority for resolving conflicts when multiple analyzers support the same path.
     * Higher values win. Default is 0.
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Discovers the analysis units (modules) of the repository snapshot.
     * For JVM this finds Maven/Gradle sub-modules; for Go each {@code go.mod}
     * is a module.
     */
    List<DiscoveredProject> scanProjects(RepoSnapshot snapshot);

    /**
     * Wraps a single directory as one analysis unit (fallback when the
     * directory has no recognized module structure).
     */
    DiscoveredProject scanSingleProject(Path projectRoot, String projectName);

    /**
     * Runs the deep analysis pipeline (parse, graph build, store to
     * Neo4j/MySQL) for the given modules.
     *
     * @return names of modules that were degraded (e.g. deep analyzer
     *         service unavailable and a weaker fallback was used)
     */
    List<String> analyzeDeep(AnalysisContext ctx, List<DiscoveredProject> projects, RepoSnapshot snapshot);
}
