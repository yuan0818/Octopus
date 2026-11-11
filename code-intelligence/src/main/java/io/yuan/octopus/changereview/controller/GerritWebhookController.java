package io.yuan.octopus.changereview.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import io.yuan.octopus.changereview.service.ChangeReviewProducer;
import io.yuan.octopus.changereview.service.ChangeReviewTaskService;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.infra.common.CredentialEncryptor;
import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Receives Gerrit webhook events (REQ-CTR-001). Validates the secret token against
 * the matched {@link RepoConfig}, parses {@code patchset-created} / {@code change-merged}
 * (other events are logged and ignored per BR-CTR-001), applies patchset idempotency +
 * supersession, creates a {@link ChangeReviewTask} (PENDING), publishes to the work
 * queue, and returns {@code 202} immediately (BR-CTR-007).
 */
@RestController
@RequestMapping("/api/webhook")
public class GerritWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GerritWebhookController.class);
    /** 日志中 payload 的截断长度（避免敏感内容/超大 payload 打满日志。 */
    private static final int PAYLOAD_LOG_LIMIT = 500;

    private final RepoConfigRepository repoConfigRepository;
    private final ChangeReviewTaskRepository taskRepository;
    private final ChangeReviewTaskService taskService;
    private final ChangeReviewProducer producer;
    private final CredentialEncryptor credentialEncryptor;
    private final boolean enabled;

    public GerritWebhookController(RepoConfigRepository repoConfigRepository,
                                  ChangeReviewTaskRepository taskRepository,
                                  ChangeReviewTaskService taskService,
                                  ChangeReviewProducer producer,
                                  CredentialEncryptor credentialEncryptor,
                                  @Value("${octopus.change-review.enabled:true}") boolean enabled) {
        this.repoConfigRepository = repoConfigRepository;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.producer = producer;
        this.credentialEncryptor = credentialEncryptor;
        this.enabled = enabled;
    }

    @PostMapping("/gerrit")
    public ResponseEntity<Result<Map<String, Object>>> handleGerrit(
            @RequestHeader(value = "${octopus.gerrit.webhook-token-header:X-Gerrit-Token}", required = false) String token,
            @RequestBody String payload) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Result.error(410, "change-review disabled"));
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(payload).getAsJsonObject();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Result.error(400, "invalid JSON payload"));
        }

        String type = optString(json, "type");
        if (!"patchset-created".equals(type) && !"change-merged".equals(type)) {
            log.info("Ignoring Gerrit event type: {}", type);
            return ResponseEntity.accepted().body(Result.success(Map.of("ignored", type)));
        }

        // Parse change / patchset / project / branch（全程 null 安全，畸形 payload 返回 400 而非 500）
        JsonObject change = optObject(json, "change");
        String project = optString(change, "project");
        if (project == null) project = optString(json, "project");
        String branch = optString(change, "branch");
        if (branch == null) branch = optString(json, "branch");
        String changeNumber = extractChangeNumber(change, json);
        JsonObject patchSet = optObject(json, "patchSet");
        if (patchSet == null) patchSet = optObject(json, "patchset");
        Integer patchsetNumber = patchSet != null ? optInt(patchSet, "number") : null;

        if (project == null || changeNumber == null || patchsetNumber == null) {
            log.warn("Incomplete Gerrit payload (project/change/patchset missing): {}", truncate(payload));
            return ResponseEntity.badRequest().body(Result.error(400, "incomplete payload"));
        }

        // Match RepoConfig by platform=GERRIT + exact project name.
        // 子串查询只做数据库预筛，再按 URL 解析出的 project 名精确比对（避免 core 命中 platform-core）。
        final String projectName = project;
        List<RepoConfig> candidates = repoConfigRepository
                .findByPlatformAndRepoUrlContaining("GERRIT", project)
                .stream()
                .filter(rc -> GerritProjectNames.matches(rc.getRepoUrl(), projectName))
                .toList();
        if (candidates.isEmpty()) {
            log.info("Gerrit event for unconfigured repo project={} ignored", project);
            return ResponseEntity.accepted().body(Result.success(Map.of("ignored", "repo not configured")));
        }

        // Validate webhook secret (BR-CTR-002)。同一 project 配了多个分支/实例时，
        // 取 secret 与 token 匹配的那一条，而不是任意第一条。
        Optional<RepoConfig> matched = candidates.stream()
                .filter(rc -> rc.getWebhookSecretEncrypted() != null
                        && tokenMatches(token, decryptQuietly(rc)))
                .findFirst();
        if (matched.isEmpty()) {
            log.warn("Gerrit webhook token mismatch for project={} ({} candidate config(s))",
                    project, candidates.size());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, "WEBHOOK_AUTH_FAILED"));
        }
        RepoConfig repoConfig = matched.get();

        // Idempotency: same (change, patchset) already has a task → return existing (BR-CTR-049)
        Optional<ChangeReviewTask> existing = taskRepository
                .findByRepoConfigIdAndChangeNumberAndPatchsetNumber(repoConfig.getId(), changeNumber, patchsetNumber);
        if (existing.isPresent()) {
            log.info("Idempotent re-delivery for change {} patchset {} → existing task {}",
                    changeNumber, patchsetNumber, existing.get().getId());
            return ResponseEntity.accepted().body(Result.success(Map.of(
                    "taskId", existing.get().getId(), "status", existing.get().getStatus())));
        }

        // Patchset supersession + create（supersede 的 @Modifying 更新需要事务，收敛在 service 内）
        ChangeReviewTask newTask = ChangeReviewTask.builder()
                .repoConfigId(repoConfig.getId())
                .platform("GERRIT")
                .changeNumber(changeNumber)
                .patchsetNumber(patchsetNumber)
                .targetBranch(branch != null ? branch : repoConfig.getBranch())
                .status("PENDING")
                .triggerSource("WEBHOOK")
                .currentPhase("FETCH_MERGE")
                .build();
        ChangeReviewTask task = taskService.createAndSupersede(newTask);
        try {
            producer.publish(task.getId());
        } catch (Exception e) {
            // MQ 不可用：任务已落库但无法投递，标记失败供人工 /retry（避免永久滞留 PENDING）
            task.setStatus("FAILED");
            task.setFailureType("CALLBACK_FAILED");
            task.setErrorMessage("MQ publish failed: " + e.getMessage());
            taskRepository.save(task);
            log.error("Failed to publish change-review task {}: {}", task.getId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(502, "MQ_PUBLISH_FAILED"));
        }

        log.info("Created change-review task {} for change {} patchset {} (repo {})",
                task.getId(), changeNumber, patchsetNumber, repoConfig.getId());
        return ResponseEntity.accepted().body(Result.success(Map.of(
                "taskId", task.getId(), "status", "PENDING")));
    }

    private boolean tokenMatches(String provided, String expected) {
        if (provided == null || expected == null) return false;
        // 常量时间比较，防时序侧信道
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /** 解密失败（密钥轮换、脏数据）视为不匹配，不让单条坏配置把请求打成 500。 */
    private String decryptQuietly(RepoConfig repoConfig) {
        try {
            return credentialEncryptor.decrypt(repoConfig.getWebhookSecretEncrypted());
        } catch (RuntimeException e) {
            log.warn("Cannot decrypt webhook secret of repo config {}: {}", repoConfig.getId(), e.getMessage());
            return null;
        }
    }

    private String truncate(String payload) {
        return payload.length() <= PAYLOAD_LOG_LIMIT ? payload : payload.substring(0, PAYLOAD_LOG_LIMIT) + "...(truncated)";
    }

    private String extractChangeNumber(JsonObject change, JsonObject root) {
        // Gerrit payload: change.number (int) or change.id (full "project~branch~number") or change._number
        if (change != null) {
            if (change.has("number") && change.get("number").isJsonPrimitive()) {
                return change.get("number").getAsString();
            }
            if (change.has("_number") && change.get("_number").isJsonPrimitive()) {
                return change.get("_number").getAsString();
            }
            String id = optString(change, "id");
            if (id != null) {
                int last = id.lastIndexOf('~');
                return last >= 0 ? id.substring(last + 1) : id;
            }
        }
        return optString(root, "change");
    }

    private String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement e = obj.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** null 安全的对象字段读取：缺失 / JSON null / 非对象一律返回 null（不抛 ClassCastException）。 */
    private JsonObject optObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) return null;
        return obj.getAsJsonObject(key);
    }

    private Integer optInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement e = obj.get(key);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : null;
    }
}
