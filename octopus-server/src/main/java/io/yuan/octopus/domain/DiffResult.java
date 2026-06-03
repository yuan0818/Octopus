package io.yuan.octopus.domain;

import java.util.Set;

/**
 * Represents the set of Java source files that changed between two Git commits.
 *
 * <ul>
 *   <li>{@code changedFilePaths} – absolute paths of added or modified {@code .java} files</li>
 *   <li>{@code deletedFilePaths} – absolute paths of deleted {@code .java} files</li>
 * </ul>
 *
 * An empty {@code DiffResult} (both sets are empty) means the repository was
 * updated but no {@code .java} files were touched, so analysis can be skipped.
 */
public record DiffResult(Set<String> changedFilePaths, Set<String> deletedFilePaths) {

    /** Returns {@code true} when no Java source files changed. */
    public boolean isEmpty() {
        return changedFilePaths.isEmpty() && deletedFilePaths.isEmpty();
    }
}

