package io.yuan.octopus.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects the project language and dispatches analysis to the matching
 * {@link ProjectAnalyzer} adapter.
 *
 * <p>All registered analyzers are auto-discovered via Spring component scanning.
 * When multiple analyzers support the same path, the one with the highest
 * {@link ProjectAnalyzer#getPriority() priority} wins.
 */
@Service
public class AnalysisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatcher.class);

    private final List<ProjectAnalyzer> analyzers;

    public AnalysisDispatcher(List<ProjectAnalyzer> analyzers) {
        this.analyzers = analyzers;
        log.info("Registered {} project analyzer(s): {}",
                analyzers.size(),
                analyzers.stream().map(ProjectAnalyzer::getLanguage).toList());
    }

    /**
     * Finds the best matching analyzer for the given project root.
     *
     * @param projectRoot the directory to probe
     * @return the matching analyzer, or empty if no language is detected
     */
    public Optional<ProjectAnalyzer> detectAnalyzer(Path projectRoot) {
        return analyzers.stream()
                .filter(a -> a.supports(projectRoot))
                .max(Comparator.comparingInt(ProjectAnalyzer::getPriority));
    }

    /**
     * Returns all analyzers that support the given path, ordered by priority (highest first).
     */
    public List<ProjectAnalyzer> detectAllAnalyzers(Path projectRoot) {
        return analyzers.stream()
                .filter(a -> a.supports(projectRoot))
                .sorted(Comparator.comparingInt(ProjectAnalyzer::getPriority).reversed())
                .toList();
    }

    /**
     * Returns the JVM analyzer (always available).
     */
    public JvmProjectAnalyzer getJvmAnalyzer() {
        return (JvmProjectAnalyzer) analyzers.stream()
                .filter(a -> a instanceof JvmProjectAnalyzer)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("JvmProjectAnalyzer not registered"));
    }

    /**
     * Lists all registered analyzer languages.
     */
    public List<String> getSupportedLanguages() {
        return analyzers.stream().map(ProjectAnalyzer::getLanguage).toList();
    }
}
