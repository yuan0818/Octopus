package io.yuan.octopus.domain;

import java.nio.file.Path;

/**
 * Result of merging a Gerrit patchset commit onto the current HEAD of its target
 * branch (the "simulated post-merge" state, ADR-006).
 *
 * <ul>
 *   <li>{@code baseCommitId}     – the target branch HEAD at merge time (real-time base)</li>
 *   <li>{@code patchsetCommitId} – the patchset commit that was merged in</li>
 *   <li>{@code mergeCommitId}    – the merge commit SHA produced (analysis commitId); {@code null} on conflict</li>
 *   <li>{@code localPath}        – absolute path to the local working tree the merge ran in</li>
 *   <li>{@code conflict}         – {@code true} when the merge produced conflicts (caller must fail the task)</li>
 *   <li>{@code conflictDetail}   – human-readable conflict detail for the Gerrit comment (branch@commit)</li>
 * </ul>
 *
 * <p>The merge tree exists only locally; nothing is pushed to the remote (BR-CTR-017).
 */
public record MergeResult(String baseCommitId,
                          String patchsetCommitId,
                          String mergeCommitId,
                          Path localPath,
                          boolean conflict,
                          String conflictDetail) {

    public static MergeResult conflict(String baseCommitId, String patchsetCommitId,
                                        Path localPath, String conflictDetail) {
        return new MergeResult(baseCommitId, patchsetCommitId, null, localPath, true, conflictDetail);
    }

    public static MergeResult merged(String baseCommitId, String patchsetCommitId,
                                      String mergeCommitId, Path localPath) {
        return new MergeResult(baseCommitId, patchsetCommitId, mergeCommitId, localPath, false, null);
    }
}
