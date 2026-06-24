package io.yuan.octopus.analyzer;

import java.nio.file.Path;

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
}
