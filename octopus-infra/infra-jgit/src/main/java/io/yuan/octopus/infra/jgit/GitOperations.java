package io.yuan.octopus.infra.jgit;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over JGit operations: clone, fetch, checkout, list tags, diff.
 */
public interface GitOperations {

    RepoSnapshot cloneRepo(String repoUrl, Path localPath, AuthConfig auth);

    RepoSnapshot fetchAndCheckout(String repoUrl, Path localPath, String ref, AuthConfig auth);

    List<String> listTags(Path localPath);

    DiffResult computeDiff(Path localPath, String fromRef, String toRef);

    record RepoSnapshot(Path repoLocalPath, String commitId) {}

    record DiffResult(List<String> changedFilePaths, List<String> deletedFilePaths) {
        public boolean isEmpty() {
            return changedFilePaths.isEmpty() && deletedFilePaths.isEmpty();
        }
    }
}
