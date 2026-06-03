package io.yuan.octopus.entity;

import java.nio.file.Path;

/**
 * Snapshot of a locally cloned repository at a specific tag.
 *
 * @param repoLocalPath  absolute path to the locally cloned repository
 * @param repoUrl        remote URL of the repository
 * @param commitId       full SHA of the commit that the tag points to
 * @param tag            the Git tag name that was checked out (e.g. {@code v1.2.0});
 *                       {@code null} when operating on a plain branch HEAD
 */
public record RepoSnapshot(Path repoLocalPath, String repoUrl, String commitId, String tag) {

}
