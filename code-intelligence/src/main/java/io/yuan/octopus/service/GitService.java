package io.yuan.octopus.service;

import io.yuan.octopus.common.AuthType;
import io.yuan.octopus.domain.DiffResult;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.entity.RepoSnapshot;
import io.yuan.octopus.exception.GitOperationException;
import io.yuan.octopus.infra.common.CredentialEncryptor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    private final CredentialEncryptor credentialEncryptor;
    private final Path localRepoBase;
    private final boolean shallowClone;

    public GitService(CredentialEncryptor credentialEncryptor,
                      @Value("${spring.git.local-repo-base:/data/repos}") String localRepoBase,
                      @Value("${octopus.git.shallow-clone:true}") boolean shallowClone) {
        this.credentialEncryptor = credentialEncryptor;
        this.localRepoBase = Path.of(localRepoBase);
        this.shallowClone = shallowClone;
    }

    // -------------------------------------------------------------------------
    // Tag-aware sync
    // -------------------------------------------------------------------------

    /**
     * Clones the repository (if not already present) or fetches the latest remote
     * refs, leaving the working tree on the configured branch HEAD.
     * Used for quick connectivity checks and project scanning without a tag.
     */
    public RepoSnapshot cloneOrPull(RepoConfig repoConfig) {
        String repoName = extractRepoName(repoConfig.getRepoUrl());
        Path localPath = localRepoBase.resolve(repoName);
        File localDir = localPath.toFile();

        try {
            if (isGitRepo(localPath)) {
                fetchAll(localDir, repoConfig);
            } else {
                cloneRepo(repoConfig, localDir, true);
            }
            String commitId = getHeadCommitId(localDir);
            return new RepoSnapshot(localPath, repoConfig.getRepoUrl(), commitId, null);
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Git operation failed for repo: " + repoConfig.getRepoUrl(), e);
        }
    }

    /**
     * Clones (or updates) the repository, fetches all tags, then checks out the
     * specified tag in a detached-HEAD state.
     *
     * @param repoConfig repository configuration
     * @param tag        the Git tag to check out (e.g. {@code v1.2.0})
     * @return a {@link RepoSnapshot} pointing to the commit the tag resolves to
     */
    public RepoSnapshot syncAndCheckout(RepoConfig repoConfig, String tag) {
        String repoName = extractRepoName(repoConfig.getRepoUrl());
        Path localPath = localRepoBase.resolve(repoName);
        File localDir = localPath.toFile();

        try {
            if (isGitRepo(localPath)) {
                fetchAll(localDir, repoConfig);
            } else {
                cloneRepo(repoConfig, localDir, false);
            }

            checkoutTag(localDir, tag);
            String commitId = getHeadCommitId(localDir);
            return new RepoSnapshot(localPath, repoConfig.getRepoUrl(), commitId, tag);
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException(
                    "Git sync and checkout failed for repo: " + repoConfig.getRepoUrl() + " tag: " + tag, e);
        }
    }

    /**
     * Returns all tag names in the remote repository, sorted lexicographically in
     * descending order (newest semantic versions tend to sort last; reverse gives
     * the most recent first for simple cases).
     */
    public List<String> listTags(RepoConfig repoConfig) {
        String repoName = extractRepoName(repoConfig.getRepoUrl());
        Path localPath = localRepoBase.resolve(repoName);
        File localDir = localPath.toFile();

        try {
            if (isGitRepo(localPath)) {
                fetchAll(localDir, repoConfig);
            } else {
                cloneRepo(repoConfig, localDir, true);
            }

            try (Git git = Git.open(localDir)) {
                return git.tagList().call().stream()
                        .map(Ref::getName)
                        .map(name -> name.replace("refs/tags/", ""))
                        .sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList());
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to list tags for: " + repoConfig.getRepoUrl(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Tag-level diff
    // -------------------------------------------------------------------------

    /**
     * Computes the set of changed and deleted {@code .java} files between two tags.
     *
     * <p>Returns {@code Optional.empty()} when the diff cannot be computed (e.g.
     * {@code fromTag} is {@code null} on first analysis, or an error occurs), which
     * signals to the caller that a full re-analysis is required.
     *
     * <p>Returns {@code Optional.of(emptyDiff)} when both tags are identical or no
     * Java files changed, allowing the caller to skip analysis entirely.
     *
     * @param localPath local repository root
     * @param fromTag   the previously analysed tag (may be {@code null})
     * @param toTag     the current target tag
     */
    public Optional<DiffResult> computeDiff(Path localPath, String fromTag, String toTag) {
        if (fromTag == null) {
            log.info("No previous tag recorded for diff – full analysis required");
            return Optional.empty();
        }
        if (fromTag.equals(toTag)) {
            log.info("Tag {} already analysed – no changes", toTag);
            return Optional.of(new DiffResult(Set.of(), Set.of()));
        }

        try (Git git = Git.open(localPath.toFile())) {
            Repository repo = git.getRepository();

            ObjectId fromTreeId = resolveTagTree(repo, fromTag);
            ObjectId toTreeId   = resolveTagTree(repo, toTag);

            if (fromTreeId == null || toTreeId == null) {
                log.warn("Cannot resolve tag '{}' or '{}' – falling back to full analysis", fromTag, toTag);
                return Optional.empty();
            }

            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, fromTreeId);

                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, toTreeId);

                List<DiffEntry> entries = git.diff()
                        .setOldTree(oldTree)
                        .setNewTree(newTree)
                        .call();

                Set<String> changedFiles = new HashSet<>();
                Set<String> deletedFiles = new HashSet<>();

                for (DiffEntry entry : entries) {
                    switch (entry.getChangeType()) {
                        case ADD, MODIFY, COPY -> {
                            String path = entry.getNewPath();
                            if (path.endsWith(".java")) {
                                changedFiles.add(localPath.resolve(path).toString());
                            }
                        }
                        case RENAME -> {
                            String oldPath = entry.getOldPath();
                            if (oldPath.endsWith(".java")) {
                                deletedFiles.add(localPath.resolve(oldPath).toString());
                            }
                            String newPath = entry.getNewPath();
                            if (newPath.endsWith(".java")) {
                                changedFiles.add(localPath.resolve(newPath).toString());
                            }
                        }
                        case DELETE -> {
                            String path = entry.getOldPath();
                            if (path.endsWith(".java")) {
                                deletedFiles.add(localPath.resolve(path).toString());
                            }
                        }
                    }
                }

                log.info("Diff {} → {}: {} changed, {} deleted Java files",
                        fromTag, toTag, changedFiles.size(), deletedFiles.size());

                return Optional.of(new DiffResult(changedFiles, deletedFiles));
            }
        } catch (Exception e) {
            log.warn("Failed to compute diff ({} → {}), falling back to full analysis: {}",
                    fromTag, toTag, e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a tag name to the root tree {@link ObjectId} of the commit it points to.
     * Handles both lightweight and annotated tags.
     */
    private ObjectId resolveTagTree(Repository repo, String tag) throws Exception {
        // Try refs/tags/<tag> first, then bare name (for commit SHAs / branch tips)
        ObjectId id = repo.resolve("refs/tags/" + tag);
        if (id == null) {
            id = repo.resolve(tag);
        }
        if (id == null) {
            return null;
        }

        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRetainBody(false);
            RevObject obj = walk.parseAny(id);
            RevCommit commit = walk.parseCommit(walk.peel(obj));
            return commit.getTree().getId();
        }
    }

    private void checkoutTag(File localDir, String tag) {
        try (Git git = Git.open(localDir)) {
            git.checkout().setName("refs/tags/" + tag).call();
        } catch (Exception e) {
            throw new GitOperationException("Failed to checkout tag: " + tag, e);
        }
    }

    /**
     * Fetch all branches and tags from the remote (replaces plain pull for
     * tag-based workflows so that new tags are discovered).
     */
    private void fetchAll(File localDir, RepoConfig repoConfig) {
        try (Git git = Git.open(localDir)) {
            var fetchCommand = git.fetch()
                    .setRefSpecs(
                            new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                            new RefSpec("+refs/tags/*:refs/tags/*"))
                    .setForceUpdate(true);
            configureAuth(fetchCommand, repoConfig);
            fetchCommand.call();
        } catch (Exception e) {
            throw new GitOperationException("Failed to fetch repository: " + repoConfig.getRepoUrl(), e);
        }
    }

    private void cloneRepo(RepoConfig repoConfig, File localDir, boolean shallow) {
        try {
            var cloneCommand = Git.cloneRepository()
                    .setURI(repoConfig.getRepoUrl())
                    .setDirectory(localDir)
                    .setBranch(repoConfig.getBranch());

            if (shallow && shallowClone) {
                cloneCommand.setDepth(1);
                cloneCommand.setBranchesToClone(List.of(repoConfig.getBranch()));
                log.info("Shallow clone (depth=1) for branch {}: {}", repoConfig.getBranch(), repoConfig.getRepoUrl());
            }

            configureAuth(cloneCommand, repoConfig);
            cloneCommand.call().close();
        } catch (Exception e) {
            throw new GitOperationException("Failed to clone repository: " + repoConfig.getRepoUrl(), e);
        }
    }

    private String getHeadCommitId(File localDir) {
        try (Git git = Git.open(localDir)) {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) {
                throw new GitOperationException("HEAD commit not found in repository");
            }
            return head.getName();
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to read HEAD commit", e);
        }
    }

    private <T extends TransportCommand<?, ?>> void configureAuth(T command, RepoConfig repoConfig) {
        if (repoConfig.getAuthType() == AuthType.HTTP) {
            String password = credentialEncryptor.decrypt(repoConfig.getHttpCredentialEncrypted());
            command.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(repoConfig.getHttpUsername(), password));
        } else if (repoConfig.getAuthType() == AuthType.SSH) {
            SshSessionFactory sshSessionFactory = buildSshSessionFactory(repoConfig);
            command.setTransportConfigCallback(transport -> {
                if (transport instanceof SshTransport sshTransport) {
                    sshTransport.setSshSessionFactory(sshSessionFactory);
                }
            });
        }
    }

    private SshSessionFactory buildSshSessionFactory(RepoConfig repoConfig) {
        var builder = new SshdSessionFactoryBuilder()
                .setPreferredAuthentications("publickey")
                .setHomeDirectory(new File(System.getProperty("user.home")))
                .setSshDirectory(new File(System.getProperty("user.home"), ".ssh"));

        if (repoConfig.getSshPrivateKeyPath() != null) {
            Path keyPath = Path.of(repoConfig.getSshPrivateKeyPath());
            builder.setDefaultIdentities(dir -> List.of(keyPath));
        }

        if (repoConfig.getSshKnownHostsPath() != null) {
            Path knownHostsPath = Path.of(repoConfig.getSshKnownHostsPath());
            builder.setDefaultKnownHostsFiles(dir -> List.of(knownHostsPath));
        }

        if (repoConfig.getSshPassphraseEncrypted() != null) {
            String passphrase = credentialEncryptor.decrypt(repoConfig.getSshPassphraseEncrypted());
            builder.setKeyPasswordProvider(cp -> new KeyPasswordProvider() {
                @Override
                public char[] getPassphrase(URIish uri, int attempt) {
                    return passphrase.toCharArray();
                }

                @Override
                public void setAttempts(int maxNumberOfAttempts) {
                    // no-op
                }

                @Override
                public boolean keyLoaded(URIish uri, int attempt, Exception error) {
                    return attempt < 1;
                }
            });
        }

        return builder.build(null);
    }

    static String extractRepoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new GitOperationException("Repository URL cannot be empty");
        }
        String url = repoUrl.replaceAll("/+$", "");
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        int lastSlash = url.lastIndexOf('/');
        int lastColon = url.lastIndexOf(':');
        int pos = Math.max(lastSlash, lastColon);
        if (pos >= 0 && pos < url.length() - 1) {
            return url.substring(pos + 1);
        }
        return url;
    }

    private boolean isGitRepo(Path path) {
        return Files.isDirectory(path) && Files.isDirectory(path.resolve(".git"));
    }
}
