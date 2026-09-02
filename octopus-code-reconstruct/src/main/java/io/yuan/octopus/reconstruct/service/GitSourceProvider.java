package io.yuan.octopus.reconstruct.service;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过 JGit 从本地 git 仓库读取指定 commit 的文件内容，按行号提取方法体。
 * <p>
 * 与 {@code octopus-git} 服务无关——直接在 reconstruct 服务中嵌入式使用 JGit，
 * 避免跨服务 HTTP 调用的开销和 {@code git checkout} 的副作用。
 * <p>
 * 仓库路径通过 projectId 查询 {@code repo_config} 表获取 repoUrl，
 * 再拼接 {@code localRepoBase} 得到本地路径。
 * {@code Repository} 实例按仓库路径缓存（{@link ConcurrentHashMap}）。
 */
@Component
public class GitSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(GitSourceProvider.class);

    /** 仓库缺失负缓存的过期时间：之后克隆进来的仓库可以被重新发现。 */
    private static final long MISSING_REPO_CACHE_MILLIS = 5 * 60 * 1000L;

    private final Path localRepoBase;
    private final DataSource dataSource;
    private final Map<String, Repository> repoCache = new ConcurrentHashMap<>();
    /** projectId → 仓库路径缓存：避免每个类取源码都查一次 DB。 */
    private final Map<Long, String> repoPathCache = new ConcurrentHashMap<>();
    /** 已知不存在的仓库路径 → 标记时刻（带 TTL 的负缓存，仓库后克隆进来可自愈）。 */
    private final Map<String, Long> missingRepoPaths = new ConcurrentHashMap<>();

    public GitSourceProvider(
            @Value("${reconstruct.git.local-repo-base:/data/repos}") String localRepoBase,
            DataSource dataSource) {
        this.localRepoBase = Path.of(localRepoBase);
        this.dataSource = dataSource;
    }

    /**
     * 通过 JGit 读取指定 commit 中指定文件的内容，并按行号提取方法体。
     *
     * @param projectId 项目 ID（用于查找仓库路径）
     * @param commitId  commit hash（完整 SHA 或缩写均可）
     * @param filePath  文件路径（绝对路径或相对仓库根的路径均可，方法内部自动相对化）
     * @param lineStart 方法声明行号（从 1 计数）
     * @param lineEnd   方法体结束行号（从 1 计数，含边界行）
     * @return 提取结果
     */
    public SourceResult getSource(long projectId, String commitId, String filePath,
                                   int lineStart, int lineEnd) {
        if (commitId == null || commitId.isBlank()) {
            return SourceResult.notFound("commitId 为空，无法从 git 获取源码");
        }
        if (filePath == null || filePath.isBlank()) {
            return SourceResult.notFound("filePath 为空，无法从 git 获取源码 (projectId=" + projectId + ")");
        }

        try {
            Repository repo = getRepository(projectId);
            if (repo == null) {
                return SourceResult.error("找不到项目 " + projectId + " 对应的 git 仓库");
            }

            ObjectId commitObjId = repo.resolve(commitId);
            if (commitObjId == null) {
                return SourceResult.notFound("commit " + commitId + " 在仓库中不存在");
            }

            // 路径相对化：class_info.source_file_path 存储的是绝对路径，
            // 而 TreeWalk.forPath 需要相对仓库根的路径。
            String relativePath = relativizeFilePath(filePath, repo);

            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(commitObjId);
                try (TreeWalk treeWalk = TreeWalk.forPath(repo, relativePath, commit.getTree())) {
                    if (treeWalk == null) {
                        return SourceResult.notFound(
                                "文件 " + relativePath + " 在 commit " + commitId + " 中不存在");
                    }

                    ObjectId blobId = treeWalk.getObjectId(0);
                    byte[] rawContent = repo.open(blobId, org.eclipse.jgit.lib.Constants.OBJ_BLOB).getBytes();
                    String fileContent = new String(rawContent, StandardCharsets.UTF_8);

                    String methodBody = extractLines(fileContent, lineStart, lineEnd);
                    log.debug("JGit success: {}:{} lines {}-{} ({} chars)",
                            shortSha(commitId), relativePath, lineStart, lineEnd, methodBody.length());
                    return SourceResult.found(methodBody);
                }
            }
        } catch (Exception e) {
            log.warn("JGit getSource failed for project={} commit={} file={}: {}",
                    projectId, commitId, filePath, e.getMessage());
            return SourceResult.error("JGit 读取失败: " + e.getMessage());
        }
    }

    /**
     * 从完整文件内容中按行号提取方法体。
     *
     * @param content   完整文件内容
     * @param lineStart 起始行号（从 1 计数）
     * @param lineEnd   结束行号（从 1 计数）
     * @return 提取的方法体字符串
     */
    public String extractLines(String content, int lineStart, int lineEnd) {
        if (lineStart <= 0) lineStart = 1;
        String[] lines = content.split("\n", -1);
        int startIdx = Math.min(Math.max(lineStart - 1, 0), lines.length);
        int endIdx = Math.max(Math.min(lineEnd, lines.length), startIdx);
        // CRLF 文件按 \n 切分后行尾残留 \r，剥离掉避免拼出的源码带不可见字符
        for (int i = startIdx; i < endIdx; i++) {
            if (lines[i].endsWith("\r")) {
                lines[i] = lines[i].substring(0, lines[i].length() - 1);
            }
        }
        return String.join("\n", Arrays.copyOfRange(lines, startIdx, endIdx));
    }

    /** SHA 缩写（不足 8 位时原样返回，避免 substring 越界）。 */
    private String shortSha(String commitId) {
        return commitId.length() > 8 ? commitId.substring(0, 8) : commitId;
    }

    /**
     * 将文件路径转换为相对仓库根的路径。
     * <p>
     * {@code class_info.source_file_path} 存储的是绝对路径（JavaParser 写入的 {@code javaFile.toString()}），
     * 而 {@link TreeWalk#forPath} 需要相对仓库根的路径。
     * <p>
     * 处理逻辑：
     * <ul>
     *   <li>绝对路径 → 相对仓库 {@link Repository#getWorkTree()} 相对化</li>
     *   <li>已经是相对路径（不在仓库 work tree 内）→ 原样返回</li>
     *   <li>统一使用正斜杠（JGit 要求）</li>
     * </ul>
     */
    private String relativizeFilePath(String filePath, Repository repo) {
        java.io.File workTree = repo != null ? repo.getWorkTree() : null;
        return relativizeFilePath(filePath, workTree != null ? workTree.getAbsolutePath() : null);
    }

    /**
     * 将文件路径转换为相对仓库根的路径（纯函数，便于单元测试）。
     *
     * @param filePath       文件路径（绝对或相对）
     * @param repoWorkTreePath 仓库 work tree 的绝对路径（可为 null）
     * @return 相对仓库根的路径（正斜杠分隔）
     */
    static String relativizeFilePath(String filePath, String repoWorkTreePath) {
        // 已经是相对路径（无前导 / 或盘符）→ 原样返回，仅统一分隔符
        Path path = Path.of(filePath);
        if (!path.isAbsolute()) {
            return filePath.replace('\\', '/');
        }

        // 绝对路径 → 尝试相对化到仓库 work tree
        if (repoWorkTreePath != null && !repoWorkTreePath.isBlank()) {
            Path workTreePath = Path.of(repoWorkTreePath);
            try {
                Path relative = workTreePath.relativize(path);
                String result = relative.toString().replace('\\', '/');
                // relativize 成功且不包含 ".."（说明 path 确实在 work tree 内）
                if (!result.startsWith("..")) {
                    return result;
                }
            } catch (Exception ignored) {
                // 路径不在同一文件系统根下，保留原路径
            }
        }

        // 兜底：原样返回（统一分隔符）
        return filePath.replace('\\', '/');
    }

    // ── 仓库管理 ─────────────────────────────────────────────────────

    /**
     * 根据 projectId 获取或创建 JGit Repository 实例。
     * 先从缓存查找，未命中时查询数据库定位仓库路径后创建。
     * <p>
     * 注意：{@code openRepository} 可能返回 null（仓库路径不存在），
     * 此时将路径记入 {@link #missingRepoPaths} 以避免重复尝试打开不存在的仓库
     * （{@link ConcurrentHashMap} 不允许 null value）。
     */
    private Repository getRepository(long projectId) {
        String repoPath = repoPathCache.computeIfAbsent(projectId, this::resolveRepoPath);
        if (repoPath == null) {
            // computeIfAbsent 不缓存 null：查不到配置时每次都会重新查库，符合预期（配置可能补上）
            return null;
        }
        Long missingSince = missingRepoPaths.get(repoPath);
        if (missingSince != null) {
            if (System.currentTimeMillis() - missingSince < MISSING_REPO_CACHE_MILLIS) {
                return null;
            }
            missingRepoPaths.remove(repoPath); // 负缓存过期：重新尝试打开（仓库可能已被克隆进来）
        }
        Repository cached = repoCache.get(repoPath);
        if (cached != null) {
            return cached;
        }
        Repository opened = openRepository(repoPath);
        if (opened == null) {
            missingRepoPaths.put(repoPath, System.currentTimeMillis());
            return null;
        }
        // 并发场景下后打开的实例覆盖缓存，先关闭避免泄漏
        Repository existing = repoCache.putIfAbsent(repoPath, opened);
        if (existing != null) {
            try { opened.close(); } catch (Exception ignored) {}
            return existing;
        }
        return opened;
    }

    /**
     * 通过数据库查询 projectId 对应的仓库路径。
     * <p>
     * analyzed_project → repo_config (repo_url) → extract repoName → localRepoBase/repoName
     */
    private String resolveRepoPath(long projectId) {
        String[] info = findProjectInfo(projectId);
        String repoUrl = info[0];
        String projectPath = info[1];
        if (repoUrl != null) {
            String repoName = extractRepoName(repoUrl);
            return localRepoBase.resolve(repoName).toString();
        }
        // 本地分析项目（无 repo_config）：项目路径自身或其上层是 git 工作区
        if (projectPath != null) {
            return findGitWorkTree(projectPath);
        }
        log.warn("Project {} has no repo_config and no project_path", projectId);
        return null;
    }

    /** 从项目路径向上找 .git（与 code-intelligence LocalGitDetector 的探测边界一致：自身或直接父目录）。 */
    private String findGitWorkTree(String projectPath) {
        Path dir = Path.of(projectPath);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        for (Path p : new Path[]{dir, dir.getParent()}) {
            if (p != null && Files.isDirectory(p.resolve(".git"))) {
                return p.toString();
            }
        }
        return null;
    }

    /** 查询 analyzed_project 的 repo_url（repo 驱动）与 project_path（本地）。 */
    private String[] findProjectInfo(long projectId) {
        String sql = """
            SELECT rc.repo_url, jp.project_path
            FROM analyzed_project jp
            LEFT JOIN repo_config rc ON jp.repo_config_id = rc.id
            WHERE jp.id = ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString("repo_url"), rs.getString("project_path")};
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load project info for {}: {}", projectId, e.getMessage());
        }
        return new String[]{null, null};
    }

    /**
     * 从 repoUrl 中提取仓库名（如 "https://github.com/org/my-app.git" → "my-app"）。
     * 复用与 GitService 相同的逻辑。
     */
    static String extractRepoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return null;
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
     * 打开或创建本地 git 仓库的 JGit Repository 实例。
     */
    private Repository openRepository(String repoPath) {
        Path path = Path.of(repoPath);
        if (!Files.isDirectory(path) || !Files.isDirectory(path.resolve(".git"))) {
            log.warn("Git repo not found at {}", repoPath);
            return null;
        }
        try {
            return FileRepositoryBuilder.create(path.resolve(".git").toFile());
        } catch (IOException e) {
            log.error("Failed to open git repository at {}: {}", repoPath, e.getMessage());
            return null;
        }
    }

    /**
     * 清理缓存中的 Repository 实例（资源释放）。
     * 同时清除 missingRepoPaths 中的占位记录，使下次访问时重新尝试打开。
     */
    public void closeRepository(String repoPath) {
        Repository repo = repoCache.remove(repoPath);
        if (repo != null) {
            repo.close();
        }
        missingRepoPaths.remove(repoPath);
    }
}
