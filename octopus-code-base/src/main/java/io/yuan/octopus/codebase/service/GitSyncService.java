package io.yuan.octopus.codebase.service;

import io.yuan.octopus.codebase.entity.RepoConfig;
import io.yuan.octopus.infra.common.CredentialEncryptor;
import jakarta.annotation.PreDestroy;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地仓库同步服务（octopus-code-base）。
 * <p>
 * 并发约束：同一仓库的本地 clone 是共享可变状态，公开方法通过 per-repo
 * {@link ReentrantLock} 串行化（进程内；多实例部署需文件锁）。
 * <p>
 * 凭据：HTTP 密码 / SSH 口令以密文存于 {@code *_encrypted} 列，使用前经
 * {@link CredentialEncryptor} 解密；SSH 会话工厂按仓库缓存并在停机时关闭
 * （每次新建且不关闭会泄漏 MINA sshd 内嵌资源）。
 */
@Service
public class GitSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitSyncService.class);

    private final CredentialEncryptor credentialEncryptor;
    private final Path localRepoRoot;

    private final ConcurrentHashMap<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SshdSessionFactory> sshFactories = new ConcurrentHashMap<>();

    public GitSyncService(CredentialEncryptor credentialEncryptor,
                          @Value("${codebase.local-repo-root:/data/repos}") String localRepoRoot) {
        this.credentialEncryptor = credentialEncryptor;
        this.localRepoRoot = Path.of(localRepoRoot);
    }

    /** Clone or pull the repository. Returns the current commit ID. */
    public String cloneOrPull(RepoConfig config) {
        return withRepoLock(config, () -> {
            String repoName = requireRepoName(config);
            File localDir = localRepoRoot.resolve(repoName).toFile();
            if (isGitRepo(localDir.toPath())) {
                fetchAll(localDir, config);
            } else {
                cloneRepo(config, localDir);
            }
            return getHeadCommitId(localDir);
        });
    }

    /** Fetch and checkout a specific tag. Returns the commit ID. */
    public String checkoutTag(RepoConfig config, String tag) {
        return withRepoLock(config, () -> {
            String repoName = requireRepoName(config);
            File localDir = localRepoRoot.resolve(repoName).toFile();
            if (!isGitRepo(localDir.toPath())) {
                cloneRepo(config, localDir);
            }
            fetchAll(localDir, config);
            try (Git git = Git.open(localDir)) {
                git.checkout().setName("refs/tags/" + tag).call();
            }
            return getHeadCommitId(localDir);
        });
    }

    /** List all tags. */
    public List<String> listTags(RepoConfig config) {
        return withRepoLock(config, () -> {
            String repoName = requireRepoName(config);
            File localDir = localRepoRoot.resolve(repoName).toFile();
            if (!isGitRepo(localDir.toPath())) {
                cloneRepo(config, localDir);
            }
            try (Git git = Git.open(localDir)) {
                return git.tagList().call().stream()
                        .map(Ref::getName)
                        .map(name -> name.replace("refs/tags/", ""))
                        .sorted(Comparator.reverseOrder())
                        .toList();
            }
        });
    }

    /** Get HEAD commit ID of the local repo. */
    public String getHeadCommitId(File localDir) {
        try (Git git = Git.open(localDir)) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head != null ? head.getName() : null;
        } catch (Exception e) {
            log.warn("Failed to read HEAD commit: {}", e.getMessage());
            return null;
        }
    }

    /** Extract repo name from URL (e.g. "https://github.com/org/my-app.git" → "my-app"). */
    public static String extractRepoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return null;
        String url = repoUrl.replaceAll("/+$", "");
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        int lastSlash = url.lastIndexOf('/');
        int lastColon = url.lastIndexOf(':');
        int pos = Math.max(lastSlash, lastColon);
        return pos >= 0 && pos < url.length() - 1 ? url.substring(pos + 1) : url;
    }

    @PreDestroy
    void shutdown() {
        sshFactories.values().forEach(factory -> {
            try {
                factory.close();
            } catch (Exception e) {
                log.warn("Failed to close SshdSessionFactory: {}", e.getMessage());
            }
        });
        sshFactories.clear();
    }

    // ── Private ──

    @FunctionalInterface
    private interface GitAction<T> {
        T run() throws Exception;
    }

    private <T> T withRepoLock(RepoConfig config, GitAction<T> action) {
        ReentrantLock lock = repoLocks.computeIfAbsent(requireRepoName(config), k -> new ReentrantLock());
        lock.lock();
        try {
            return action.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Git operation failed: " + config.getRepoUrl(), e);
        }
    }

    private String requireRepoName(RepoConfig config) {
        String repoName = extractRepoName(config.getRepoUrl());
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("Cannot derive repo name from URL: " + config.getRepoUrl());
        }
        return repoName;
    }

    private void cloneRepo(RepoConfig config, File localDir) {
        try {
            var cmd = Git.cloneRepository()
                    .setURI(config.getRepoUrl())
                    .setDirectory(localDir)
                    .setBranch(config.getBranch())
                    .setDepth(1);
            configureAuth(cmd, config);
            cmd.call().close();
        } catch (Exception e) {
            throw new RuntimeException("Clone failed: " + config.getRepoUrl(), e);
        }
    }

    private void fetchAll(File localDir, RepoConfig config) {
        try (Git git = Git.open(localDir)) {
            var cmd = git.fetch()
                    .setRefSpecs(
                            new org.eclipse.jgit.transport.RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                            new org.eclipse.jgit.transport.RefSpec("+refs/tags/*:refs/tags/*"))
                    .setForceUpdate(true);
            configureAuth(cmd, config);
            cmd.call();
        } catch (Exception e) {
            throw new RuntimeException("Fetch failed: " + config.getRepoUrl(), e);
        }
    }

    private void configureAuth(TransportCommand<?, ?> cmd, RepoConfig config) {
        if ("HTTP".equals(config.getAuthType())) {
            // 密文列必须解密后使用；httpCredentialEncrypted 直接当密码会认证失败
            String password = config.getHttpCredentialEncrypted() != null
                    ? credentialEncryptor.decrypt(config.getHttpCredentialEncrypted()) : null;
            cmd.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(config.getHttpUsername(), password));
        } else if ("SSH".equals(config.getAuthType())) {
            TransportConfigCallback callback = transport -> {
                if (transport instanceof SshTransport ssh) {
                    ssh.setSshSessionFactory(sshFactoryFor(config));
                }
            };
            cmd.setTransportConfigCallback(callback);
        }
    }

    /** 按仓库缓存 SSH 会话工厂（每次 build 且不 close 会泄漏 MINA sshd 资源）。 */
    private SshdSessionFactory sshFactoryFor(RepoConfig config) {
        return sshFactories.compute(config.getId(), (id, existing) -> {
            SshdSessionFactory factory = buildSshFactory(config);
            if (existing != null) {
                try {
                    existing.close();
                } catch (Exception e) {
                    log.debug("Failed to close replaced SshdSessionFactory for repo {}: {}", id, e.getMessage());
                }
            }
            return factory;
        });
    }

    private SshdSessionFactory buildSshFactory(RepoConfig config) {
        SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
                .setPreferredAuthentications("publickey")
                .setHomeDirectory(new File(System.getProperty("user.home")))
                .setSshDirectory(new File(System.getProperty("user.home"), ".ssh"));
        if (config.getSshPrivateKeyPath() != null) {
            builder.setDefaultIdentities(dir -> List.of(Path.of(config.getSshPrivateKeyPath())));
        }
        if (config.getSshKnownHostsPath() != null) {
            builder.setDefaultKnownHostsFiles(dir -> List.of(Path.of(config.getSshKnownHostsPath())));
        }
        if (config.getSshPassphraseEncrypted() != null) {
            String passphrase = credentialEncryptor.decrypt(config.getSshPassphraseEncrypted());
            builder.setKeyPasswordProvider(cp -> new KeyPasswordProvider() {
                @Override
                public char[] getPassphrase(URIish uri, int attempt) {
                    return attempt <= 1 ? passphrase.toCharArray() : null;
                }

                @Override
                public void setAttempts(int maxAttempts) {
                    // 单次尝试即可：口令错误直接失败，避免无限重试
                }

                @Override
                public boolean keyLoaded(URIish uri, int attempt, Exception error) {
                    return error == null;
                }
            });
        }
        return builder.build(null);
    }

    private boolean isGitRepo(Path path) {
        return Files.isDirectory(path) && Files.isDirectory(path.resolve(".git"));
    }
}
