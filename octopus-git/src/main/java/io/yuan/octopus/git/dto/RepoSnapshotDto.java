package io.yuan.octopus.git.dto;

/**
 * Snapshot of a locally synced repository.
 */
public record RepoSnapshotDto(
    String repoLocalPath,
    String repoUrl,
    String commitId,
    String tag
) {}
