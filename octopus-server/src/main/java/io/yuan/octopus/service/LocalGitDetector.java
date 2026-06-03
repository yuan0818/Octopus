package io.yuan.octopus.service;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Detects git repository metadata (branch and HEAD commit) for a local path.
 * Uses JGit's {@link FileRepositoryBuilder} to walk up the directory tree, handling
 * worktrees, submodules, packed-refs, and detached-HEAD states.
 */
@Service
public class LocalGitDetector {

    private static final Logger log = LoggerFactory.getLogger(LocalGitDetector.class);

    public record GitInfo(String branch, String commitId) {}

    /**
     * Detects git branch and HEAD commit for the given path.
     * Walks up the directory tree to find the nearest {@code .git} directory.
     *
     * @param projectPath the directory to start from (usually the project root)
     * @return detected git info, or empty if the path is not inside a git repository
     */
    public Optional<GitInfo> detect(Path projectPath) {
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder()
                    .findGitDir(projectPath.toFile())
                    .setMustExist(true);

            if (builder.getGitDir() == null) {
                return Optional.empty();
            }

            try (Repository repo = builder.build()) {
                String branch = resolveBranch(repo);
                String commitId = resolveCommitId(repo);
                if (commitId == null) {
                    return Optional.empty();
                }
                log.debug("Detected git info for {}: branch={}, commit={}", projectPath, branch, commitId);
                return Optional.of(new GitInfo(branch, commitId));
            }

        } catch (IOException e) {
            log.debug("Failed to detect git info for {}: {}", projectPath, e.getMessage());
            return Optional.empty();
        }
    }

    private String resolveBranch(Repository repo) throws IOException {
        String branch = repo.getBranch();
        // When HEAD is detached, getBranch() returns the raw commit SHA
        ObjectId headId = repo.resolve(Constants.HEAD);
        if (headId != null && headId.getName().equals(branch)) {
            return "HEAD"; // detached
        }
        return branch;
    }

    private String resolveCommitId(Repository repo) {
        try {
            ObjectId head = repo.resolve(Constants.HEAD);
            return head != null ? head.getName() : null;
        } catch (IOException e) {
            log.debug("Could not resolve HEAD commit: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the git root directory for the given path, or null if not in a git repo.
     * The root is the directory that contains the {@code .git} folder.
     */
    public File findGitRoot(Path projectPath) {
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder()
                    .findGitDir(projectPath.toFile());
            if (builder.getGitDir() == null) return null;
            File gitDir = builder.getGitDir();
            // For a standard .git dir, the parent is the work tree
            return gitDir.getName().equals(".git") ? gitDir.getParentFile() : gitDir.getParentFile();
        } catch (Exception e) {
            return null;
        }
    }
}
