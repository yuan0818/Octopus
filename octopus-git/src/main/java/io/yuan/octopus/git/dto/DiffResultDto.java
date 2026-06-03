package io.yuan.octopus.git.dto;

import java.util.Set;

/**
 * Result of a tag-to-tag diff, listing changed and deleted .java files.
 */
public record DiffResultDto(
    Set<String> changedFilePaths,
    Set<String> deletedFilePaths
) {
    public boolean isEmpty() {
        return changedFilePaths.isEmpty() && deletedFilePaths.isEmpty();
    }
}
