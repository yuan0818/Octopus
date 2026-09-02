package io.yuan.octopus.codebase.controller;

import io.yuan.octopus.codebase.entity.RepoConfig;
import io.yuan.octopus.codebase.repository.RepoConfigRepository;
import io.yuan.octopus.codebase.service.GitSyncService;
import io.yuan.octopus.infra.common.CredentialEncryptor;
import io.yuan.octopus.infra.common.Result;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/api/repos")
public class RepoConfigController {

    private static final Logger log = LoggerFactory.getLogger(RepoConfigController.class);

    private final RepoConfigRepository repoConfigRepository;
    private final GitSyncService gitSyncService;
    private final CredentialEncryptor credentialEncryptor;

    /** 初次 clone 的有界线程池（裸 new Thread 无上限，会被连点打爆）。 */
    private final ExecutorService syncExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "repo-initial-sync");
        t.setDaemon(true);
        return t;
    });

    /** per-repo 互斥：防止同一仓库被并发触发初次 clone/sync 损坏本地 .git。 */
    private final ConcurrentHashMap<Long, ReentrantLock> syncLocks = new ConcurrentHashMap<>();

    public RepoConfigController(RepoConfigRepository repoConfigRepository,
                                GitSyncService gitSyncService,
                                CredentialEncryptor credentialEncryptor) {
        this.repoConfigRepository = repoConfigRepository;
        this.gitSyncService = gitSyncService;
        this.credentialEncryptor = credentialEncryptor;
    }

    @PreDestroy
    void shutdown() {
        syncExecutor.shutdownNow();
    }

    @GetMapping
    public Result<List<RepoConfig>> list() {
        return Result.success(repoConfigRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Result<RepoConfig>> get(@PathVariable Long id) {
        return repoConfigRepository.findById(id)
                .map(Result::success)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Result.error(404, "REPO_NOT_FOUND")));
    }

    @PostMapping
    public Result<RepoConfig> create(@RequestBody RepoConfig config) {
        encryptSecrets(config, config);
        config.setSyncStatus("pending");
        config.setHttpCredential(null);
        config.setSshPassphrase(null);
        config.setPlatformToken(null);
        config.setWebhookSecret(null);
        RepoConfig saved = repoConfigRepository.save(config);
        // Trigger initial clone asynchronously
        syncExecutor.execute(() -> runInitialSync(saved.getId()));
        return Result.success(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<RepoConfig>> update(@PathVariable Long id, @RequestBody RepoConfig config) {
        RepoConfig existing = repoConfigRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "REPO_NOT_FOUND"));
        }
        if (notBlank(config.getRepoUrl())) existing.setRepoUrl(config.getRepoUrl());
        if (notBlank(config.getBranch())) existing.setBranch(config.getBranch());
        if (notBlank(config.getAuthType())) existing.setAuthType(config.getAuthType());
        // 可选字段：客户端不传（null）时保留原值，避免部分更新变成清空
        if (config.getHttpUsername() != null) existing.setHttpUsername(config.getHttpUsername());
        if (config.getSshPrivateKeyPath() != null) existing.setSshPrivateKeyPath(config.getSshPrivateKeyPath());
        if (config.getSshKnownHostsPath() != null) existing.setSshKnownHostsPath(config.getSshKnownHostsPath());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        // 平台字段
        if (notBlank(config.getPlatform())) existing.setPlatform(config.getPlatform());
        existing.setPlatformUrl(config.getPlatformUrl());
        // 凭据：仅在提交明文时更新密文（密文字段有 @JsonIgnore，不接受客户端直传密文）
        encryptSecrets(existing, config);
        return ResponseEntity.ok(Result.success(repoConfigRepository.save(existing)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        repoConfigRepository.deleteById(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<Result<Map<String, String>>> sync(@PathVariable Long id) {
        RepoConfig config = repoConfigRepository.findById(id).orElse(null);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "REPO_NOT_FOUND"));
        }
        ReentrantLock lock = syncLocks.computeIfAbsent(id, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(409, "SYNC_IN_PROGRESS"));
        }
        try {
            config.setSyncStatus("syncing");
            repoConfigRepository.save(config);
            String commitId = gitSyncService.cloneOrPull(config);
            config.setLastCommitId(commitId);
            config.setLastSyncAt(LocalDateTime.now());
            config.setSyncStatus("ready");
            repoConfigRepository.save(config);
            return ResponseEntity.ok(Result.success(Map.of("commitId", String.valueOf(commitId))));
        } catch (Exception e) {
            log.error("Manual sync failed for repo {}: {}", id, e.getMessage(), e);
            config.setSyncStatus("error");
            repoConfigRepository.save(config);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(502, "SYNC_FAILED: " + e.getMessage()));
        } finally {
            lock.unlock();
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Result<Map<String, Object>>> status(@PathVariable Long id) {
        RepoConfig config = repoConfigRepository.findById(id).orElse(null);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "REPO_NOT_FOUND"));
        }
        // 首次同步完成前 lastCommitId/lastSyncAt 为 null，Map.of 不允许 null 值
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("syncStatus", config.getSyncStatus());
        data.put("lastCommitId", config.getLastCommitId());
        data.put("lastSyncAt", config.getLastSyncAt());
        return ResponseEntity.ok(Result.success(data));
    }

    @GetMapping("/{id}/tags")
    public ResponseEntity<Result<List<String>>> tags(@PathVariable Long id) {
        RepoConfig config = repoConfigRepository.findById(id).orElse(null);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error(404, "REPO_NOT_FOUND"));
        }
        return ResponseEntity.ok(Result.success(gitSyncService.listTags(config)));
    }

    // ── Private ──

    /**
     * 把明文凭据（@Transient 请求字段）加密写入 target 的 encrypted 列。
     * 明文为 null/空时不覆盖既有密文；写完清空明文，保证不落库、不回显。
     */
    private void encryptSecrets(RepoConfig target, RepoConfig request) {
        if (notBlank(request.getHttpCredential())) {
            target.setHttpCredentialEncrypted(credentialEncryptor.encrypt(request.getHttpCredential()));
        }
        if (notBlank(request.getSshPassphrase())) {
            target.setSshPassphraseEncrypted(credentialEncryptor.encrypt(request.getSshPassphrase()));
        }
        if (notBlank(request.getPlatformToken())) {
            target.setPlatformTokenEncrypted(credentialEncryptor.encrypt(request.getPlatformToken()));
        }
        if (notBlank(request.getWebhookSecret())) {
            target.setWebhookSecretEncrypted(credentialEncryptor.encrypt(request.getWebhookSecret()));
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 异步初次同步：状态流转落库 + 失败留痕（日志 + error 状态）。 */
    private void runInitialSync(Long repoId) {
        ReentrantLock lock = syncLocks.computeIfAbsent(repoId, k -> new ReentrantLock());
        lock.lock();
        try {
            RepoConfig config = repoConfigRepository.findById(repoId).orElse(null);
            if (config == null) {
                return;
            }
            config.setSyncStatus("syncing");
            config = repoConfigRepository.save(config);
            String commitId = gitSyncService.cloneOrPull(config);
            config.setSyncStatus("ready");
            config.setLastCommitId(commitId);
            config.setLastSyncAt(LocalDateTime.now());
            repoConfigRepository.save(config);
            log.info("Initial sync completed for repo {} at {}", repoId, commitId);
        } catch (Exception e) {
            log.error("Initial sync failed for repo {}: {}", repoId, e.getMessage(), e);
            repoConfigRepository.findById(repoId).ifPresent(config -> {
                config.setSyncStatus("error");
                repoConfigRepository.save(config);
            });
        } finally {
            lock.unlock();
        }
    }
}
