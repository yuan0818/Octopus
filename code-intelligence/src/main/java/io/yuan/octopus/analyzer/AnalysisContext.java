package io.yuan.octopus.analyzer;

import java.nio.file.Path;

/**
 * Carries all the context an analyzer needs to perform its work.
 *
 * @param projectRoot     the root directory of the workspace / repo checkout
 * @param branch          the git branch (or "local" for local-only analysis)
 * @param commitId        the commit SHA, or {@code null} for non-git projects
 * @param repoConfigId    the repo config entity ID, or {@code null} for local analysis
 * @param taskId          the analysis task entity ID (for status updates)
 */
public record AnalysisContext(
    Path projectRoot,
    String branch,
    String commitId,
    Long repoConfigId,
    Long taskId
) {}
