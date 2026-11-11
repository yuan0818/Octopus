package io.yuan.octopus.service;

import io.yuan.octopus.common.AuthType;
import io.yuan.octopus.domain.DiffResult;
import io.yuan.octopus.domain.FileLineChanges;
import io.yuan.octopus.domain.MergeResult;
import io.yuan.octopus.dto.response.BranchInfo;
import io.yuan.octopus.dto.response.CommitInfo;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.entity.RepoSnapshot;
import io.yuan.octopus.exception.GitOperationException;
import io.yuan.octopus.infra.common.CredentialEncryptor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 本地仓库的 Git 操作服务。
 * <p>
 * <b>并发约束：</b>同一仓库的本地 clone 目录是共享可变状态（fetch/reset/checkout/merge
 * 都会改写 {@code .git} 与工作区），所有变更型公开方法通过进程内 per-repo
 * {@link ReentrantLock} 串行化，避免 JGit 锁文件冲突与工作区互相踩踏。
 * 注意：锁仅在单实例部署内有效——多实例部署需改用基于 {@code .git} 目录的文件锁。
 * 只读操作（diff/行号计算）不加锁，读取窗口内的工作区一致性由上层任务编排保证。
 */
@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    private final CredentialEncryptor credentialEncryptor;
    private final Path localRepoBase;
    private final boolean shallowClone;

    /** 按仓库名（本地目录名）串行化变更型 Git 操作的进程内锁。 */
    private final ConcurrentHashMap<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();

    @FunctionalInterface
    private interface GitAction<T> {
        T run() throws Exception;
    }

    /** 在 per-repo 锁内执行变更型 Git 操作。 */
    private <T> T withRepoLock(RepoConfig repoConfig, GitAction<T> action) {
        ReentrantLock lock = repoLocks.computeIfAbsent(
                extractRepoName(repoConfig.getRepoUrl()), k -> new ReentrantLock());
        lock.lock();
        try {
            return action.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Git operation failed for repo: " + repoConfig.getRepoUrl(), e);
        }
    }

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
        return withRepoLock(repoConfig, () -> doCloneOrPull(repoConfig));
    }

    private RepoSnapshot doCloneOrPull(RepoConfig repoConfig) {
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
        return withRepoLock(repoConfig, () -> doSyncAndCheckout(repoConfig, tag));
    }

    private RepoSnapshot doSyncAndCheckout(RepoConfig repoConfig, String tag) {
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
        return withRepoLock(repoConfig, () -> doListTags(repoConfig));
    }

    private List<String> doListTags(RepoConfig repoConfig) {
        String repoName = extractRepoName(repoConfig.getRepoUrl());
        Path localPath = localRepoBase.resolve(repoName);
        File localDir = localPath.toFile();

        try {
            if (isGitRepo(localPath)) {
                fetchAll(localDir, repoConfig);
            } else {
                cloneRepo(repoConfig, localDir, true);
                // shallow clone 只带配置的单分支，需补一次 fetchAll 才能看到全部标签
                fetchAll(localDir, repoConfig);
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

    /**
     * 列出远程仓库的所有分支，解析每个分支 HEAD 指向的 commit SHA。
     * 仅返回 {@code refs/remotes/origin/} 下的远程跟踪分支，过滤掉
     * {@code origin/HEAD} 符号引用。
     */
    public List<BranchInfo> listBranches(RepoConfig repoConfig) {
        return withRepoLock(repoConfig, () -> doListBranches(repoConfig));
    }

    private List<BranchInfo> doListBranches(RepoConfig repoConfig) {
        String repoName = extractRepoName(repoConfig.getRepoUrl());
        Path localPath = localRepoBase.resolve(repoName);
        File localDir = localPath.toFile();

        try {
            if (isGitRepo(localPath)) {
                fetchAll(localDir, repoConfig);
            } else {
                cloneRepo(repoConfig, localDir, true);
                // shallow clone 只带配置的单分支，首次调用需补 fetchAll 才能列出全部分支
                fetchAll(localDir, repoConfig);
            }

            try (Git git = Git.open(localDir)) {
                Repository repo = git.getRepository();
                List<Ref> refs = git.branchList()
                        .setListMode(ListBranchCommand.ListMode.REMOTE)
                        .call();
                String prefix = "refs/remotes/origin/";
                List<BranchInfo> branches = new ArrayList<>();
                for (Ref ref : refs) {
                    String fullName = ref.getName();
                    if (!fullName.startsWith(prefix)) continue;
                    String shortName = fullName.substring(prefix.length());
                    // 过滤 origin/HEAD 符号引用
                    if ("HEAD".equals(shortName)) continue;
                    branches.add(new BranchInfo(shortName, ref.getObjectId().getName()));
                }
                branches.sort(Comparator.comparing(BranchInfo::name));
                return branches;
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to list branches for: " + repoConfig.getRepoUrl(), e);
        }
    }

    /**
     * 列出指定分支最近的 {@code limit} 条 commit。
     *
     * @param branch 分支名（短名，如 {@code main}）
     * @param limit  最多返回的 commit 数量，&lt;=0 时按 50 处理
     */
    public List<CommitInfo> listCommits(RepoConfig repoConfig, String branch, int limit) {
        return withRepoLock(repoConfig, () -> doListCommits(repoConfig, branch, limit));
    }

    private List<CommitInfo> doListCommits(RepoConfig repoConfig, String branch, int limit) {
        // 上限 200：limit 是客户端可控参数，不封顶会被恶意/误传大值拖垮 log walk
        int max = limit <= 0 ? 50 : Math.min(limit, 200);
        String repoName = extractRepoName(repoConfig.getRepoUrl());
        Path localPath = localRepoBase.resolve(repoName);
        File localDir = localPath.toFile();

        try {
            if (isGitRepo(localPath)) {
                fetchAll(localDir, repoConfig);
            } else {
                cloneRepo(repoConfig, localDir, true);
                // shallow clone 只带配置的单分支，补一次 fetchAll 保证其他分支的 commit 可查
                fetchAll(localDir, repoConfig);
            }

            try (Git git = Git.open(localDir)) {
                Repository repo = git.getRepository();
                ObjectId branchHead = repo.resolve("refs/remotes/origin/" + branch);
                if (branchHead == null) {
                    // 兜底：尝试本地分支引用
                    branchHead = repo.resolve(branch);
                }
                if (branchHead == null) {
                    log.warn("Cannot resolve branch '{}' in {}, returning empty commit list", branch, repoConfig.getRepoUrl());
                    return List.of();
                }
                Iterable<RevCommit> log = git.log()
                        .add(branchHead)
                        .setMaxCount(max)
                        .call();
                List<CommitInfo> commits = new ArrayList<>();
                for (RevCommit c : log) {
                    String message = c.getShortMessage();
                    if (message != null && message.length() > 80) {
                        message = message.substring(0, 80);
                    }
                    String author = c.getAuthorIdent() != null ? c.getAuthorIdent().getName() : null;
                    commits.add(new CommitInfo(
                            c.getName(),
                            message,
                            author,
                            c.getCommitTime()));
                }
                return commits;
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to list commits for: " + repoConfig.getRepoUrl() + " branch: " + branch, e);
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

            DiffResult result = diffJavaFiles(git, repo, fromTreeId, toTreeId, localPath);
            log.info("Diff {} → {}: {} changed, {} deleted Java files",
                    fromTag, toTag, result.changedFilePaths().size(), result.deletedFilePaths().size());
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("Failed to compute diff ({} → {}), falling back to full analysis: {}",
                    fromTag, toTag, e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Change-triggered merge (REQ-CTR-003, ADR-006)
    // -------------------------------------------------------------------------

    /**
     * Fetches a Gerrit patchset ref {@code refs/changes/{nn}/{change}/{patchset}}
     * into the local clone and returns the patchset commit SHA.
     *
     * <p>{@code nn} is the last two digits of the change number (zero-padded),
     * per Gerrit's ref sharding convention.
     *
     * @param repoConfig repository configuration
     * @param change     Gerrit change number (numeric)
     * @param patchset   patchset number (numeric)
     * @return the full SHA of the fetched patchset commit
     */
    public String fetchPatchsetRef(RepoConfig repoConfig, String change, int patchset) {
        return withRepoLock(repoConfig, () -> doFetchPatchsetRef(repoConfig, change, patchset));
    }

    private String doFetchPatchsetRef(RepoConfig repoConfig, String change, int patchset) {
        String changeNum = validateNumeric(change, "change");
        String shard = String.format("%02d", Integer.parseInt(changeNum) % 100);
        String refName = "refs/changes/" + shard + "/" + changeNum + "/" + patchset;

        String repoName = extractRepoName(repoConfig.getRepoUrl());
        File localDir = localRepoBase.resolve(repoName).toFile();

        try {
            if (!isGitRepo(localDir.toPath())) {
                cloneRepo(repoConfig, localDir, false);
            }
            try (Git git = Git.open(localDir)) {
                RefSpec spec = new RefSpec("+" + refName + ":" + refName);
                var fetch = git.fetch().setRefSpecs(spec).setForceUpdate(true);
                configureAuth(fetch, repoConfig);
                fetch.call();

                Ref ref = git.getRepository().findRef(refName);
                if (ref == null) {
                    throw new GitOperationException("Patchset ref not found after fetch: " + refName
                            + " (check Gerrit HTTP account has read permission on refs/changes/*)");
                }
                ObjectId commitId = git.getRepository().getRefDatabase().peel(ref).getPeeledObjectId();
                if (commitId == null) {
                    commitId = ref.getObjectId();
                }
                return commitId.getName();
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to fetch patchset ref " + refName
                    + " for " + repoConfig.getRepoUrl(), e);
        }
    }

    /**
     * Merges a patchset commit onto the current HEAD of the target branch
     * (the real-time base), producing a simulated post-merge state.
     *
     * <p>The merge tree exists only in the local working tree; nothing is pushed
     * to the remote (BR-CTR-017). On conflict the task must be failed with
     * {@code MERGE_CONFLICT}; on success the returned merge commit SHA is the
     * analysis commitId.
     *
     * @param repoConfig        repository configuration
     * @param branch            target branch (e.g. {@code main})
     * @param patchsetCommitId  full SHA of the patchset commit (from {@link #fetchPatchsetRef})
     * @return a {@link MergeResult} describing base / patchset / merge commits or conflict
     */
    public MergeResult mergePatchsetOntoBase(RepoConfig repoConfig, String branch, String patchsetCommitId) {
        return withRepoLock(repoConfig, () -> doMergePatchsetOntoBase(repoConfig, branch, patchsetCommitId));
    }

    private MergeResult doMergePatchsetOntoBase(RepoConfig repoConfig, String branch, String patchsetCommitId) {
        String repoName = extractRepoName(repoConfig.getRepoUrl());
        File localDir = localRepoBase.resolve(repoName).toFile();

        try {
            if (isGitRepo(localDir.toPath())) {
                fetchAll(localDir, repoConfig);
            } else {
                cloneRepo(repoConfig, localDir, false);
            }

            try (Git git = Git.open(localDir)) {
                Repository repo = git.getRepository();

                // real-time base = target branch current HEAD (BR-CTR-014)
                ObjectId baseId = repo.resolve("refs/remotes/origin/" + branch);
                if (baseId == null) {
                    baseId = repo.resolve(branch);
                }
                if (baseId == null) {
                    throw new GitOperationException("Cannot resolve base branch '" + branch
                            + "' in " + repoConfig.getRepoUrl());
                }
                String baseCommitId = baseId.getName();

                ObjectId patchsetId = repo.resolve(patchsetCommitId);
                if (patchsetId == null) {
                    throw new GitOperationException("Cannot resolve patchset commit " + patchsetCommitId);
                }

                // Clean working tree, then detach HEAD at base commit (BR-CTR-015)
                git.reset().setMode(ResetCommand.ResetType.HARD).call();
                git.checkout().setName(baseCommitId).call();

                // Merge patchset commit onto base; never push.
                org.eclipse.jgit.api.MergeResult jgitResult = git.merge()
                        .include(patchsetId)
                        .setCommit(true)
                        .setMessage("Octopus simulated merge: patchset " + patchsetCommitId + " onto " + branch)
                        .call();

                org.eclipse.jgit.api.MergeResult.MergeStatus status = jgitResult.getMergeStatus();
                if (status == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING) {
                    String detail = branch + "@" + baseCommitId.substring(0, 12)
                            + " (patchset " + patchsetCommitId.substring(0, 12) + ")";
                    log.warn("Merge conflict for change patchset {} onto {}: {}", patchsetCommitId, detail, jgitResult.getConflicts());
                    // 清理冲突残留（HARD reset 回 base）：避免 unmerged path 毒化后续
                    // checkoutTag 等不带 reset 兜底的操作，导致该仓库后续分析全部失败
                    git.reset().setMode(ResetCommand.ResetType.HARD).call();
                    return MergeResult.conflict(baseCommitId, patchsetCommitId, localDir.toPath(), detail);
                }

                ObjectId mergeId = repo.resolve("HEAD");
                if (mergeId == null) {
                    throw new GitOperationException("HEAD not resolvable after merge (unexpected)");
                }
                log.info("Simulated merge complete: base={} patchset={} merge={}",
                        baseCommitId, patchsetCommitId, mergeId.getName());
                return MergeResult.merged(baseCommitId, patchsetCommitId, mergeId.getName(), localDir.toPath());
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to merge patchset " + patchsetCommitId
                    + " onto " + branch + " for " + repoConfig.getRepoUrl(), e);
        }
    }

    /**
     * Computes changed and deleted {@code .java} files between two commit SHAs
     * (commit-to-commit variant of {@link #computeDiff}, used by {@code ChangeMethodDetector}).
     *
     * @param localPath     local repository root
     * @param baseCommitId  base commit SHA (the previously-analysed or base HEAD)
     * @param headCommitId  head commit SHA (the patchset / merge commit)
     * @return a {@link DiffResult} (possibly empty when no Java files changed)
     */
    public DiffResult computeCommitDiff(Path localPath, String baseCommitId, String headCommitId) {
        try (Git git = Git.open(localPath.toFile())) {
            Repository repo = git.getRepository();

            ObjectId fromTreeId = resolveCommitTree(repo, baseCommitId);
            ObjectId toTreeId = resolveCommitTree(repo, headCommitId);
            if (fromTreeId == null || toTreeId == null) {
                throw new GitOperationException("Cannot resolve commit tree for base="
                        + baseCommitId + " head=" + headCommitId);
            }
            return diffJavaFiles(git, repo, fromTreeId, toTreeId, localPath);
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to compute commit diff (" + baseCommitId
                    + " → " + headCommitId + ")", e);
        }
    }

    /**
     * Computes per-file change detail with changed line ranges (NEW-file 1-based
     * coordinates) between two commit SHAs. Used by {@code ChangeMethodDetector}
     * to map diff hunks to methods. Only {@code .java} files are returned.
     *
     * @param localPath     local repository root
     * @param baseCommitId  base commit SHA
     * @param headCommitId  head commit SHA (patchset / merge commit)
     * @return list of {@link FileLineChanges}, one per changed {@code .java} file
     */
    /** Includes non-code files so impact callers can explicitly report unsupported changes. */
    public List<String> computeChangedPaths(Path localPath,String baseCommitId,String targetCommitId) {
        try(Git git=Git.open(localPath.toFile());ObjectReader reader=git.getRepository().newObjectReader()) {
            var before=new CanonicalTreeParser();before.reset(reader,resolveCommitTree(git.getRepository(),baseCommitId));
            var after=new CanonicalTreeParser();after.reset(reader,resolveCommitTree(git.getRepository(),targetCommitId));
            return git.diff().setOldTree(before).setNewTree(after).setShowNameAndStatusOnly(true).call().stream()
                    .map(e->e.getChangeType()==DiffEntry.ChangeType.DELETE?e.getOldPath():e.getNewPath()).distinct().sorted().toList();
        }catch(Exception e){throw new GitOperationException("Failed to list changed paths",e);}
    }

    public List<FileLineChanges> computeChangedLineRanges(Path localPath,
                                                           String baseCommitId,
                                                           String headCommitId) {
        try (Git git = Git.open(localPath.toFile())) {
            Repository repo = git.getRepository();
            ObjectId fromTreeId = resolveCommitTree(repo, baseCommitId);
            ObjectId toTreeId = resolveCommitTree(repo, headCommitId);
            if (fromTreeId == null || toTreeId == null) {
                throw new GitOperationException("Cannot resolve commit tree for base="
                        + baseCommitId + " head=" + headCommitId);
            }

            List<DiffEntry> entries;
            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, fromTreeId);
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, toTreeId);
                entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
            }

            List<FileLineChanges> result = new ArrayList<>();
            for (DiffEntry entry : entries) {
                String changeType;
                String newRelPath;
                String oldRelPath = null;
                switch (entry.getChangeType()) {
                    case ADD, MODIFY, COPY -> {
                        changeType = entry.getChangeType() == DiffEntry.ChangeType.ADD ? "ADD" : "MODIFY";
                        newRelPath = entry.getNewPath();
                        if (entry.getChangeType() != DiffEntry.ChangeType.ADD) oldRelPath = entry.getOldPath();
                    }
                    case RENAME -> {
                        changeType = "RENAME";
                        newRelPath = entry.getNewPath();
                        oldRelPath = entry.getOldPath();
                    }
                    case DELETE -> {
                        changeType = "DELETE";
                        newRelPath = entry.getOldPath();
                        oldRelPath = entry.getOldPath();
                    }
                    default -> {
                        continue;
                    }
                }
                if (!isSourceFile(newRelPath)
                        && !(oldRelPath != null && isSourceFile(oldRelPath))) {
                    continue;
                }

                List<int[]> ranges = computeEditLineRanges(repo, entry, false);
                List<int[]> oldRanges = computeEditLineRanges(repo, entry, true);
                String newAbs = isSourceFile(newRelPath) ? localPath.resolve(newRelPath).toString() : null;
                String oldAbs = oldRelPath != null && isSourceFile(oldRelPath)
                        ? localPath.resolve(oldRelPath).toString() : null;
                // For DELETE there is no NEW-side file; carry the old path as filePath so callers
                // can look up base-version methods by the (old) file path.
                String filePath = "DELETE".equals(changeType) ? oldAbs : newAbs;
                result.add(new FileLineChanges(filePath, oldAbs, changeType, ranges, oldRanges));
            }
            return result;
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to compute changed line ranges ("
                    + baseCommitId + " → " + headCommitId + ")", e);
        }
    }

    /**
     * Computes the NEW-file 1-based inclusive line ranges that were inserted or
     * replaced for a single {@link DiffEntry} via JGit's histogram diff. Returns
     * an empty list for ADD (whole file new — caller treats all methods as added)
     * and DELETE (no NEW-side lines).
     */
    private List<int[]> computeEditLineRanges(Repository repo, DiffEntry entry, boolean oldSide) throws Exception {
        if (entry.getChangeType() == DiffEntry.ChangeType.ADD
                || entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
            return List.of();
        }
        ObjectId oldBlob = entry.getOldId().toObjectId();
        ObjectId newBlob = entry.getNewId().toObjectId();
        if (oldBlob == null || newBlob == null) {
            return List.of();
        }
        byte[] oldBytes = repo.open(oldBlob, Constants.OBJ_BLOB).getBytes();
        byte[] newBytes = repo.open(newBlob, Constants.OBJ_BLOB).getBytes();
        RawText oldText = new RawText(oldBytes);
        RawText newText = new RawText(newBytes);
        EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, oldText, newText);
        List<int[]> ranges = new ArrayList<>();
        for (Edit edit : edits) {
            int beginB = oldSide ? edit.getBeginA() : edit.getBeginB();
            int endB = oldSide ? edit.getEndA() : edit.getEndB();
            if (endB <= beginB) {
                continue;
            }
            // Convert to 1-based inclusive [beginB+1, endB]
            ranges.add(new int[]{beginB + 1, endB});
        }
        return ranges;
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

    /**
     * Resolves a commit SHA to the root tree {@link ObjectId} (commit-to-commit diff).
     */
    private ObjectId resolveCommitTree(Repository repo, String commitSha) throws Exception {
        ObjectId id = repo.resolve(commitSha);
        if (id == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRetainBody(false);
            RevCommit commit = walk.parseCommit(id);
            return commit.getTree().getId();
        }
    }

    /**
     * Diffs two tree objects and classifies the changed/deleted {@code .java} files.
     */
    private DiffResult diffJavaFiles(Git git, Repository repo,
                                     ObjectId fromTreeId, ObjectId toTreeId, Path localPath) throws Exception {
        try (ObjectReader reader = repo.newObjectReader()) {
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, fromTreeId);

            CanonicalTreeParser newTree = new CanonicalTreeParser();
            newTree.reset(reader, toTreeId);

            List<DiffEntry> entries = git.diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .call();
            return classifyJavaDiff(entries, localPath);
        }
    }

    /** 受分析管线的源码扩展（JVM + Go）；tag 增量 diff 与变更行定位共用。 */
    private static boolean isSourceFile(String path) {
        return path != null && (path.endsWith(".java") || path.endsWith(".go"));
    }

    /**
     * Classifies diff entries into changed (added/modified/copied/renamed-to) and
     * deleted source-file absolute paths（.java / .go）。
     */
    private DiffResult classifyJavaDiff(List<DiffEntry> entries, Path localPath) {
        Set<String> changedFiles = new HashSet<>();
        Set<String> deletedFiles = new HashSet<>();
        for (DiffEntry entry : entries) {
            switch (entry.getChangeType()) {
                case ADD, MODIFY, COPY -> {
                    String path = entry.getNewPath();
                    if (isSourceFile(path)) {
                        changedFiles.add(localPath.resolve(path).toString());
                    }
                }
                case RENAME -> {
                    String oldPath = entry.getOldPath();
                    if (isSourceFile(oldPath)) {
                        deletedFiles.add(localPath.resolve(oldPath).toString());
                    }
                    String newPath = entry.getNewPath();
                    if (isSourceFile(newPath)) {
                        changedFiles.add(localPath.resolve(newPath).toString());
                    }
                }
                case DELETE -> {
                    String path = entry.getOldPath();
                    if (isSourceFile(path)) {
                        deletedFiles.add(localPath.resolve(path).toString());
                    }
                }
            }
        }
        return new DiffResult(changedFiles, deletedFiles);
    }

    /**
     * Validates that a Gerrit change number is purely numeric (guards against
     * RefSpec injection when constructing {@code refs/changes/*}).
     */
    private String validateNumeric(String value, String label) {
        if (value == null || !value.matches("\\d+")) {
            throw new GitOperationException("Invalid " + label + " (must be numeric): " + value);
        }
        return value;
    }

    private void checkoutTag(File localDir, String tag) {
        try (Git git = Git.open(localDir)) {
            // 兜底清理可能的脏工作区/unmerged path（如历史遗留的模拟 merge 冲突残留），
            // 否则 checkout 会因冲突文件失败
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
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

    /**
     * Returns the local working-tree path GitService operates on for a repo
     * ({@code localRepoBase/<repoName>}). The merge tree is left on this path;
     * callers (change-review orchestrator) read the merged tree here for analysis
     * and change detection without re-deriving the path.
     */
    public Path localRepositoryPath(RepoConfig repoConfig) {
        return localRepoBase.resolve(extractRepoName(repoConfig.getRepoUrl()));
    }

    private boolean isGitRepo(Path path) {
        return Files.isDirectory(path) && Files.isDirectory(path.resolve(".git"));
    }
}
