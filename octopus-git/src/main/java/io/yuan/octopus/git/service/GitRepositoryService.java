package io.yuan.octopus.git.service;

import io.yuan.octopus.git.dto.*;
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
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GitRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryService.class);

    private final Path localRepoBase;
    private final boolean shallowClone;

    public GitRepositoryService(
            @Value("${octopus.git.local-repo-base:/data/repos}") String localRepoBase,
            @Value("${octopus.git.shallow-clone:true}") boolean shallowClone) {
        this.localRepoBase = Path.of(localRepoBase);
        this.shallowClone = shallowClone;
    }

    // ── Public API ────────────────────────────────────────────────────

    public RepoSnapshotDto cloneOrPull(GitRepoConfig config) {
        File localDir = resolveLocalDir(config.repoUrl());
        try {
            if (isGitRepo(localDir.toPath())) {
                fetchAll(localDir, config);
            } else {
                cloneRepo(config, localDir, true);
            }
            String commitId = getHeadCommitId(localDir);
            return new RepoSnapshotDto(localDir.getAbsolutePath(), config.repoUrl(), commitId, null);
        } catch (Exception e) {
            throw new GitServiceException("cloneOrPull failed for: " + config.repoUrl(), e);
        }
    }

    public RepoSnapshotDto syncAndCheckout(GitRepoConfig config, String tag) {
        File localDir = resolveLocalDir(config.repoUrl());
        try {
            if (isGitRepo(localDir.toPath())) {
                fetchAll(localDir, config);
            } else {
                cloneRepo(config, localDir, false);
            }
            checkoutTag(localDir, tag);
            String commitId = getHeadCommitId(localDir);
            return new RepoSnapshotDto(localDir.getAbsolutePath(), config.repoUrl(), commitId, tag);
        } catch (Exception e) {
            throw new GitServiceException("syncAndCheckout failed for: " + config.repoUrl() + " tag: " + tag, e);
        }
    }

    public List<String> listTags(GitRepoConfig config) {
        File localDir = resolveLocalDir(config.repoUrl());
        try {
            if (isGitRepo(localDir.toPath())) {
                fetchAll(localDir, config);
            } else {
                cloneRepo(config, localDir, true);
            }
            try (Git git = Git.open(localDir)) {
                return git.tagList().call().stream()
                        .map(Ref::getName)
                        .map(name -> name.replace("refs/tags/", ""))
                        .sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            throw new GitServiceException("listTags failed for: " + config.repoUrl(), e);
        }
    }

    public Optional<DiffResultDto> computeDiff(String repoName, String fromTag, String toTag) {
        Path localPath = localRepoBase.resolve(repoName);
        if (fromTag == null) {
            log.info("No previous tag — full analysis required");
            return Optional.empty();
        }
        if (fromTag.equals(toTag)) {
            log.info("Tags identical — no changes");
            return Optional.of(new DiffResultDto(Set.of(), Set.of()));
        }

        try (Git git = Git.open(localPath.toFile())) {
            Repository repo = git.getRepository();
            ObjectId fromTreeId = resolveTagTree(repo, fromTag);
            ObjectId toTreeId = resolveTagTree(repo, toTag);
            if (fromTreeId == null || toTreeId == null) {
                log.warn("Cannot resolve tag tree for {} → {}", fromTag, toTag);
                return Optional.empty();
            }

            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, fromTreeId);
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, toTreeId);

                List<DiffEntry> entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
                Set<String> changed = new HashSet<>();
                Set<String> deleted = new HashSet<>();

                for (DiffEntry entry : entries) {
                    switch (entry.getChangeType()) {
                        case ADD, MODIFY, COPY -> {
                            if (entry.getNewPath().endsWith(".java"))
                                changed.add(localPath.resolve(entry.getNewPath()).toString());
                        }
                        case RENAME -> {
                            if (entry.getOldPath().endsWith(".java"))
                                deleted.add(localPath.resolve(entry.getOldPath()).toString());
                            if (entry.getNewPath().endsWith(".java"))
                                changed.add(localPath.resolve(entry.getNewPath()).toString());
                        }
                        case DELETE -> {
                            if (entry.getOldPath().endsWith(".java"))
                                deleted.add(localPath.resolve(entry.getOldPath()).toString());
                        }
                    }
                }
                log.info("Diff {} → {}: {} changed, {} deleted", fromTag, toTag, changed.size(), deleted.size());
                return Optional.of(new DiffResultDto(changed, deleted));
            }
        } catch (Exception e) {
            log.warn("Diff failed ({} → {}): {}", fromTag, toTag, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Git operations ─────────────────────────────────────────────────

    private ObjectId resolveTagTree(Repository repo, String tag) throws IOException {
        ObjectId id = repo.resolve("refs/tags/" + tag);
        if (id == null) id = repo.resolve(tag);
        if (id == null) return null;
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
            throw new GitServiceException("Failed to checkout tag: " + tag, e);
        }
    }

    private void fetchAll(File localDir, GitRepoConfig config) {
        try (Git git = Git.open(localDir)) {
            var fetchCommand = git.fetch()
                    .setRefSpecs(
                            new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                            new RefSpec("+refs/tags/*:refs/tags/*"))
                    .setForceUpdate(true);
            configureAuth(fetchCommand, config);
            fetchCommand.call();
        } catch (Exception e) {
            throw new GitServiceException("Failed to fetch: " + config.repoUrl(), e);
        }
    }

    private void cloneRepo(GitRepoConfig config, File localDir, boolean shallow) {
        try {
            var cloneCommand = Git.cloneRepository()
                    .setURI(config.repoUrl())
                    .setDirectory(localDir)
                    .setBranch(config.branch());
            if (shallow && shallowClone) {
                cloneCommand.setDepth(1);
                cloneCommand.setBranchesToClone(List.of(config.branch()));
                log.info("Shallow clone (depth=1) for {} branch {}", config.repoUrl(), config.branch());
            }
            configureAuth(cloneCommand, config);
            cloneCommand.call().close();
        } catch (Exception e) {
            throw new GitServiceException("Failed to clone: " + config.repoUrl(), e);
        }
    }

    private String getHeadCommitId(File localDir) {
        try (Git git = Git.open(localDir)) {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) throw new GitServiceException("HEAD not found");
            return head.getName();
        } catch (IOException e) {
            throw new GitServiceException("Failed to read HEAD", e);
        }
    }

    // ── Auth ───────────────────────────────────────────────────────────

    private <T extends TransportCommand<?, ?>> void configureAuth(T command, GitRepoConfig config) {
        if ("HTTP".equalsIgnoreCase(config.authType())) {
            command.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(config.httpUsername(), config.httpPassword()));
        } else if ("SSH".equalsIgnoreCase(config.authType())) {
            command.setTransportConfigCallback(transport -> {
                if (transport instanceof SshTransport ssh) {
                    ssh.setSshSessionFactory(buildSshSessionFactory(config));
                }
            });
        }
    }

    private SshSessionFactory buildSshSessionFactory(GitRepoConfig config) {
        var builder = new SshdSessionFactoryBuilder()
                .setPreferredAuthentications("publickey")
                .setHomeDirectory(new File(System.getProperty("user.home")))
                .setSshDirectory(new File(System.getProperty("user.home"), ".ssh"));

        if (config.sshPrivateKeyPath() != null && !config.sshPrivateKeyPath().isBlank()) {
            Path keyPath = Path.of(config.sshPrivateKeyPath());
            builder.setDefaultIdentities(dir -> List.of(keyPath));
        }
        if (config.sshKnownHostsPath() != null && !config.sshKnownHostsPath().isBlank()) {
            Path khPath = Path.of(config.sshKnownHostsPath());
            builder.setDefaultKnownHostsFiles(dir -> List.of(khPath));
        }
        if (config.sshPassphrase() != null && !config.sshPassphrase().isBlank()) {
            String pp = config.sshPassphrase();
            builder.setKeyPasswordProvider(cp -> new KeyPasswordProvider() {
                @Override public char[] getPassphrase(URIish uri, int attempt) { return pp.toCharArray(); }
                @Override public void setAttempts(int max) {}
                @Override public boolean keyLoaded(URIish uri, int attempt, Exception error) { return attempt < 1; }
            });
        }
        return builder.build(null);
    }

    // ── Utilities ──────────────────────────────────────────────────────

    private File resolveLocalDir(String repoUrl) {
        String name = extractRepoName(repoUrl);
        return localRepoBase.resolve(name).toFile();
    }

    static String extractRepoName(String repoUrl) {
        String url = repoUrl.replaceAll("/+$", "");
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        int lastSlash = url.lastIndexOf('/');
        int lastColon = url.lastIndexOf(':');
        int pos = Math.max(lastSlash, lastColon);
        if (pos >= 0 && pos < url.length() - 1) return url.substring(pos + 1);
        return url;
    }

    private boolean isGitRepo(Path path) {
        return Files.isDirectory(path) && Files.isDirectory(path.resolve(".git"));
    }

    // ── Exception ──────────────────────────────────────────────────────

    public static class GitServiceException extends RuntimeException {
        public GitServiceException(String message) { super(message); }
        public GitServiceException(String message, Throwable cause) { super(message, cause); }
    }
}
