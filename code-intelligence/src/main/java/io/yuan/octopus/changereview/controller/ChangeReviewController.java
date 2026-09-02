package io.yuan.octopus.changereview.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import io.yuan.octopus.changereview.service.ChangeReviewProducer;
import io.yuan.octopus.changereview.service.ChangeReviewTaskService;
import io.yuan.octopus.changereview.service.GerritRestClient;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.infra.memory.model.ReviewSession;
import io.yuan.octopus.infra.memory.service.MemoryManager;
import io.yuan.octopus.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Manual trigger + Change-dimension query API (REQ-CTR-001 manual / REQ-CTR-009 /
 * REQ-CTR-010 retry). Reuses the same pipeline as the webhook path.
 */
@RestController
@RequestMapping("/api/change-review")
public class ChangeReviewController {

    private static final Logger log = LoggerFactory.getLogger(ChangeReviewController.class);

    /** 单任务人工重试上限：超过后要求重新建任务（防止无限重试消耗资源）。 */
    private static final int MAX_RETRY_COUNT = 5;

    private final ChangeReviewTaskRepository taskRepository;
    private final ChangeReviewTaskService taskService;
    private final ChangeReviewProducer producer;
    private final GerritRestClient gerritRestClient;
    private final RepoConfigRepository repoConfigRepository;
    private final MemoryManager memoryManager;

    public ChangeReviewController(ChangeReviewTaskRepository taskRepository,
                                  ChangeReviewTaskService taskService,
                                  ChangeReviewProducer producer,
                                  GerritRestClient gerritRestClient,
                                  RepoConfigRepository repoConfigRepository,
                                  MemoryManager memoryManager) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.producer = producer;
        this.gerritRestClient = gerritRestClient;
        this.repoConfigRepository = repoConfigRepository;
        this.memoryManager = memoryManager;
    }

    /** Manual trigger: fetch latest patchset via Gerrit REST and run the pipeline. */
    @PostMapping
    public ResponseEntity<Result<Map<String, Object>>> trigger(@RequestBody Map<String, Object> body) {
        Long repoConfigId = toLong(body.get("repoConfigId"));
        Object changeObj = body.get("change");
        String change = changeObj != null ? String.valueOf(changeObj).trim() : null;
        if (repoConfigId == null || change == null || change.isBlank() || "null".equals(change)) {
            return ResponseEntity.badRequest().body(Result.error(400, "repoConfigId and change required"));
        }
        RepoConfig repoConfig = repoConfigRepository.findById(repoConfigId).orElse(null);
        if (repoConfig == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "REPO_CONFIG_NOT_FOUND"));
        }
        io.yuan.octopus.changereview.dto.PatchsetInfo patchset;
        try {
            patchset = gerritRestClient.getLatestPatchset(repoConfig, change);
        } catch (Exception e) {
            log.error("Gerrit getLatestPatchset failed for repo {} change {}: {}",
                    repoConfigId, change, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(502, "GERRIT_REST_FAILED"));
        }

        // Idempotency: same (change, patchset) already done → return existing
        Optional<ChangeReviewTask> existing = taskRepository
                .findByRepoConfigIdAndChangeNumberAndPatchsetNumber(repoConfigId, change, patchset.patchsetNumber());
        if (existing.isPresent() && isTerminal(existing.get().getStatus())) {
            return ResponseEntity.accepted().body(Result.success(Map.of(
                    "taskId", existing.get().getId(), "status", existing.get().getStatus())));
        }

        ChangeReviewTask newTask = ChangeReviewTask.builder()
                .repoConfigId(repoConfigId)
                .platform("GERRIT")
                .changeNumber(change)
                .patchsetNumber(patchset.patchsetNumber())
                .targetBranch(patchset.branch() != null ? patchset.branch() : repoConfig.getBranch())
                .status("PENDING")
                .triggerSource("MANUAL")
                .currentPhase("FETCH_MERGE")
                .build();
        ChangeReviewTask task = taskService.createAndSupersede(newTask);
        try {
            producer.publish(task.getId());
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setFailureType("CALLBACK_FAILED");
            task.setErrorMessage("MQ publish failed: " + e.getMessage());
            taskRepository.save(task);
            log.error("Failed to publish change-review task {}: {}", task.getId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(502, "MQ_PUBLISH_FAILED"));
        }
        return ResponseEntity.accepted().body(Result.success(Map.of(
                "taskId", task.getId(), "status", "PENDING")));
    }

    /** List tasks by repoConfigId / change / status（分页，缺省 0/50，size 上限 200）。 */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(required = false) Long repoConfigId,
                                            @RequestParam(required = false) String change,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest
                .of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        org.springframework.data.domain.Page<ChangeReviewTask> result;
        if (repoConfigId != null && change != null) {
            result = taskRepository.findByRepoConfigIdAndChangeNumberOrderByPatchsetNumberDesc(
                    repoConfigId, change, pageable);
        } else if (status != null) {
            result = taskRepository.findByStatus(status, pageable);
        } else {
            result = taskRepository.findAll(pageable);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", result.getContent().stream().map(this::toListEntry).toList());
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        return Result.success(data);
    }

    /** Task detail: base/patchset/merge commits + affected endpoints + per-session findings. */
    @GetMapping("/{taskId}")
    public ResponseEntity<Result<Map<String, Object>>> detail(@PathVariable Long taskId) {
        ChangeReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "TASK_NOT_FOUND"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("changeNumber", task.getChangeNumber());
        data.put("patchsetNumber", task.getPatchsetNumber());
        data.put("baseCommitId", task.getBaseCommitId());
        data.put("patchsetCommitId", task.getPatchsetCommitId());
        data.put("mergeCommitId", task.getMergeCommitId());
        data.put("status", task.getStatus());
        data.put("failureType", task.getFailureType());
        data.put("affectedEndpointCount", task.getAffectedEndpointCount());
        data.put("errorMessage", task.getErrorMessage());
        data.put("reviewSessions", loadReviewSessions(task));
        return ResponseEntity.ok(Result.success(data));
    }

    /** Retry a FAILED task. */
    @PostMapping("/{taskId}/retry")
    public ResponseEntity<Result<Map<String, Object>>> retry(@PathVariable Long taskId) {
        ChangeReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "TASK_NOT_FOUND"));
        }
        if (!"FAILED".equals(task.getStatus()) && !"PARTIALLY_COMPLETED".equals(task.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(409, "only FAILED/PARTIALLY_COMPLETED tasks can be retried"));
        }
        // 无上限重试会在 MQ 重试循环里无限消耗资源，超过上限要求人工介入（重新建任务）
        if (task.getRetryCount() != null && task.getRetryCount() >= MAX_RETRY_COUNT) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(409, "retry limit (" + MAX_RETRY_COUNT + ") reached; create a new task instead"));
        }
        task.setStatus("PENDING");
        task.setFailureType(null);
        task.setErrorMessage(null);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setCurrentPhase("FETCH_MERGE");
        task = taskRepository.save(task);
        try {
            producer.publish(taskId);
        } catch (Exception e) {
            log.error("Failed to publish retry for task {}: {}", taskId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(502, "MQ_PUBLISH_FAILED"));
        }
        return ResponseEntity.ok(Result.success(Map.of("taskId", taskId, "status", "PENDING")));
    }

    /** Re-run only the WRITEBACK phase (review succeeded but Gerrit comment failed). */
    @PostMapping("/{taskId}/rewrite")
    public ResponseEntity<Result<Map<String, Object>>> rewrite(@PathVariable Long taskId) {
        ChangeReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "TASK_NOT_FOUND"));
        }
        if (!"PARTIALLY_COMPLETED".equals(task.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(409, "only PARTIALLY_COMPLETED tasks can be rewritten"));
        }
        task.setStatus("PENDING");
        task.setFailureType(null);
        task.setErrorMessage(null);
        task.setCurrentPhase("WRITEBACK");
        task = taskRepository.save(task);
        try {
            producer.publish(taskId);
        } catch (Exception e) {
            log.error("Failed to publish rewrite for task {}: {}", taskId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(502, "MQ_PUBLISH_FAILED"));
        }
        return ResponseEntity.ok(Result.success(Map.of("taskId", taskId, "status", "PENDING")));
    }

    // ── Helpers ──

    private Map<String, Object> toListEntry(ChangeReviewTask task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.getId());
        m.put("changeNumber", task.getChangeNumber());
        m.put("patchsetNumber", task.getPatchsetNumber());
        m.put("status", task.getStatus());
        m.put("affectedEndpointCount", task.getAffectedEndpointCount());
        m.put("createdAt", task.getCreatedAt());
        return m;
    }

    private List<Map<String, Object>> loadReviewSessions(ChangeReviewTask task) {
        List<String> ids = parseSessionIds(task.getReviewSessionIds());
        if (ids.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            memoryManager.getSessionById(id).ifPresent(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId", s.getId());
                m.put("anchorClass", s.getAnchorClass());
                m.put("anchorMethod", s.getAnchorMethod());
                m.put("status", s.getStatus());
                m.put("summary", s.getSummary());
                m.put("commitId", s.getCommitId());
                m.put("fullReport", s.getFullReport());
                out.add(m);
            });
        }
        return out;
    }

    private List<String> parseSessionIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonArray()) return List.of();
            JsonArray arr = el.getAsJsonArray();
            List<String> ids = new ArrayList<>();
            for (JsonElement e : arr) {
                if (!e.isJsonNull()) ids.add(e.getAsString());
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "PARTIALLY_COMPLETED".equals(status) || "SUPERSEDED".equals(status);
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.valueOf(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}
