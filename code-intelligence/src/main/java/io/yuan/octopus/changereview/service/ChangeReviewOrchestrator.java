package io.yuan.octopus.changereview.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.changereview.dto.AffectedEndpoint;
import io.yuan.octopus.changereview.dto.ChangedMethod;
import io.yuan.octopus.changereview.dto.EndpointReviewResult;
import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.service.AnalysisOrchestrationService;
import io.yuan.octopus.service.GitService;
import io.yuan.octopus.service.RepoConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the change-triggered review pipeline state machine (REQ-CTR-002 ~ 008).
 * Resumable by {@code currentPhase}: on consumer restart the orchestrator resumes
 * from the last persisted phase, skipping completed phases using the committed
 * base/patchset/merge commit ids (ADR-004 — {@code change_review_task} is the
 * durable truth source; the analysis_task row is transient step tracking).
 *
 * <p>Phases: FETCH_MERGE → ANALYZE → DETECT_CHANGE → IMPACT → REVIEW → WRITEBACK.
 */
@Component
public class ChangeReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChangeReviewOrchestrator.class);

    public static final String PHASE_FETCH_MERGE = "FETCH_MERGE";
    public static final String PHASE_ANALYZE = "ANALYZE";
    public static final String PHASE_DETECT_CHANGE = "DETECT_CHANGE";
    public static final String PHASE_IMPACT = "IMPACT";
    public static final String PHASE_REVIEW = "REVIEW";
    public static final String PHASE_WRITEBACK = "WRITEBACK";

    private final ChangeReviewTaskRepository taskRepository;
    private final RepoConfigService repoConfigService;
    private final GitService gitService;
    private final AnalysisOrchestrationService analysisOrchestrationService;
    private final ChangeMethodDetector changeMethodDetector;
    private final AffectedEndpointService affectedEndpointService;
    private final ReviewAgentClient reviewAgentClient;
    private final GerritRestClient gerritRestClient;
    private final GerritCommentBuilder commentBuilder;
    private final Gson gson = new Gson();

    private final String octopusBaseUrl;

    public ChangeReviewOrchestrator(ChangeReviewTaskRepository taskRepository,
                                    RepoConfigService repoConfigService,
                                    GitService gitService,
                                    AnalysisOrchestrationService analysisOrchestrationService,
                                    ChangeMethodDetector changeMethodDetector,
                                    AffectedEndpointService affectedEndpointService,
                                    ReviewAgentClient reviewAgentClient,
                                    GerritRestClient gerritRestClient,
                                    GerritCommentBuilder commentBuilder,
                                    @Value("${octopus.gerrit.octopus-base-url:http://localhost:8080}") String octopusBaseUrl) {
        this.taskRepository = taskRepository;
        this.repoConfigService = repoConfigService;
        this.gitService = gitService;
        this.analysisOrchestrationService = analysisOrchestrationService;
        this.changeMethodDetector = changeMethodDetector;
        this.affectedEndpointService = affectedEndpointService;
        this.reviewAgentClient = reviewAgentClient;
        this.gerritRestClient = gerritRestClient;
        this.commentBuilder = commentBuilder;
        this.octopusBaseUrl = octopusBaseUrl;
    }

    /**
     * Runs the pipeline for a task, resuming from its persisted {@code currentPhase}.
     * Returns the final task state. Never throws — failures are persisted as
     * FAILED (fatal phases) / PARTIALLY_COMPLETED (only WRITEBACK failed; re-run
     * via {@code /rewrite} reuses the persisted REVIEW results without re-calling
     * the review agent).
     */
    public ChangeReviewTask run(Long taskId) {
        ChangeReviewTask task = taskRepository.findById(taskId).orElseThrow();
        MDC.put("taskId", String.valueOf(task.getId()));
        try {
            // 状态由消费侧 claim(PENDING→RUNNING) 原子置位，这里不再重复 markRunning
            String phase = task.getCurrentPhase() != null ? task.getCurrentPhase() : PHASE_FETCH_MERGE;

            RepoConfig repoConfig = repoConfigService.getById(task.getRepoConfigId());
            Path localPath = gitService.localRepositoryPath(repoConfig);

            // ── FETCH_MERGE ──
            if (indexBefore(phase, PHASE_ANALYZE)) {
                if (!fetchAndMerge(task, repoConfig)) return done(task);
                phase = PHASE_ANALYZE;
            }

            // ── ANALYZE ──
            if (indexBefore(phase, PHASE_DETECT_CHANGE)) {
                if (!analyze(task, repoConfig, localPath)) return done(task);
                phase = PHASE_DETECT_CHANGE;
                // startChangeAnalysis 内部会 remove 同名 MDC key，这里补回保证后续日志带 taskId
                MDC.put("taskId", String.valueOf(task.getId()));
            }

            // ── DETECT_CHANGE ──
            List<ChangedMethod> changedMethods;
            if (indexBefore(phase, PHASE_IMPACT)) {
                changedMethods = detectChanges(task, localPath);
                if (changedMethods == null) return done(task); // empty changes → completed + comment posted
                persistPhase(task, PHASE_IMPACT, null);
                phase = PHASE_IMPACT;
            } else {
                // re-detect on resume (cheap) — merge tree persists at localPath
                changedMethods = changeMethodDetector.detect(
                        localPath, task.getBaseCommitId(), task.getPatchsetCommitId(), task.getMergeCommitId());
            }

            // ── IMPACT + REVIEW + WRITEBACK ──
            List<AffectedEndpoint> affected;
            try {
                affected = affectedEndpointService.getAffectedEndpoints(
                        task.getTargetBranch(), task.getMergeCommitId(), changedMethods);
            } catch (Exception e) {
                // 影响面查询失败（如 Neo4j 不可用）不能静默当作"无影响面"——那会带着漏报结论完成审查
                log.error("Impact analysis failed for task {}: {}", task.getId(), e.getMessage(), e);
                return fail(task, "IMPACT_FAILED", "Impact analysis failed: " + e.getMessage());
            }
            task.setAffectedEndpointCount(affected.size());
            taskRepository.save(task);

            Map<String, EndpointReviewResult> results;
            if (indexBefore(phase, PHASE_WRITEBACK)) {
                results = reviewEndpoints(task, repoConfig, affected);
            } else {
                // WRITEBACK 恢复（/rewrite）：复用已持久化的 REVIEW 结果，不重跑 LLM 审查。
                // 旧任务（无持久化结果）退化为重跑 REVIEW。
                results = loadPersistedResults(task);
                if (results == null) {
                    log.info("Task {} has no persisted review results; re-running REVIEW phase", task.getId());
                    results = reviewEndpoints(task, repoConfig, affected);
                }
            }

            return writeBack(task, repoConfig, affected, results, changedMethods);
        } catch (Exception e) {
            log.error("Unhandled error in change-review task {}: {}", task.getId(), e.getMessage(), e);
            return fail(task, "REVIEW_FAILED", "Unhandled error: " + e.getMessage());
        } finally {
            MDC.remove("taskId");
        }
    }

    // ── Phases ──

    private boolean fetchAndMerge(ChangeReviewTask task, RepoConfig repoConfig) {
        String patchsetCommitId;
        try {
            patchsetCommitId = gitService.fetchPatchsetRef(
                    repoConfig, task.getChangeNumber(), task.getPatchsetNumber());
        } catch (Exception e) {
            fail(task, "FETCH_FAILED", "Fetch patchset failed: " + e.getMessage());
            return false;
        }
        try {
            var merge = gitService.mergePatchsetOntoBase(
                    repoConfig, task.getTargetBranch(), patchsetCommitId);
            if (merge.conflict()) {
                String msg = "合入冲突，base=" + merge.conflictDetail() + "，请人工解决后重试。";
                safePostComment(repoConfig, task.getChangeNumber(), msg);
                fail(task, "MERGE_CONFLICT", msg);
                return false;
            }
            task.setBaseCommitId(merge.baseCommitId());
            task.setPatchsetCommitId(patchsetCommitId);
            task.setMergeCommitId(merge.mergeCommitId());
            persistPhase(task, PHASE_ANALYZE, null);
            return true;
        } catch (Exception e) {
            fail(task, "MERGE_FAILED", "Merge failed: " + e.getMessage());
            return false;
        }
    }

    private boolean analyze(ChangeReviewTask task, RepoConfig repoConfig, Path localPath) {
        try {
            // The merge left the working tree at the merge commit on the shared clone.
            analysisOrchestrationService.startChangeAnalysis(
                    task.getRepoConfigId(), task.getTargetBranch(), task.getMergeCommitId(), localPath);
            persistPhase(task, PHASE_DETECT_CHANGE, null);
            return true;
        } catch (AnalysisOrchestrationService.AnalysisFailedException e) {
            fail(task, "ANALYSIS_FAILED", e.getMessage());
            return false;
        }
    }

    /**
     * @return changed methods, or {@code null} when no Java changes (the "no changes"
     *         path posts a comment and completes the task).
     */
    private List<ChangedMethod> detectChanges(ChangeReviewTask task, Path localPath) {
        List<ChangedMethod> changed = changeMethodDetector.detect(
                localPath, task.getBaseCommitId(), task.getPatchsetCommitId(), task.getMergeCommitId());
        if (changed.isEmpty()) {
            RepoConfig repoConfig = repoConfigService.getById(task.getRepoConfigId());
            safePostComment(repoConfig, task.getChangeNumber(), "无 Java 代码变更，跳过审查。");
            complete(task);
            return null;
        }
        return changed;
    }

    private Map<String, EndpointReviewResult> reviewEndpoints(ChangeReviewTask task, RepoConfig repoConfig,
                                                              List<AffectedEndpoint> affected) {
        Map<String, EndpointReviewResult> results = new HashMap<>();
        List<String> sessionIds = new ArrayList<>();
        for (AffectedEndpoint ep : affected) {
            EndpointReviewResult result = reviewAgentClient.reviewEndpoint(
                    ep.projectId(), ep.qualifiedClassName(), ep.methodName(),
                    task.getTargetBranch(), task.getMergeCommitId());
            results.put(ep.signature(), result);
            if (result.sessionId() != null) {
                sessionIds.add(result.sessionId());
            }
        }
        task.setReviewSessionIds(toJsonArray(sessionIds));
        // REVIEW 产物持久化：WRITEBACK 失败后 /rewrite 恢复时直接复用，不重跑 LLM 审查
        task.setReviewResults(gson.toJson(results));
        taskRepository.save(task);
        return results;
    }

    /** 反序列化已持久化的 REVIEW 结果；无数据或解析失败返回 null（调用方退化为重跑 REVIEW）。 */
    private Map<String, EndpointReviewResult> loadPersistedResults(ChangeReviewTask task) {
        String json = task.getReviewResults();
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, EndpointReviewResult> results = gson.fromJson(json,
                    new TypeToken<Map<String, EndpointReviewResult>>() {}.getType());
            return results != null && !results.isEmpty() ? results : null;
        } catch (Exception e) {
            log.warn("Failed to parse persisted review results for task {}: {}", task.getId(), e.getMessage());
            return null;
        }
    }

    private ChangeReviewTask writeBack(ChangeReviewTask task, RepoConfig repoConfig,
                                       List<AffectedEndpoint> affected,
                                       Map<String, EndpointReviewResult> results,
                                       List<ChangedMethod> changedMethods) {
        List<ChangedMethod> deleted = changedMethods.stream()
                .filter(m -> "DELETED".equals(m.changeType())).toList();
        String body = commentBuilder.build(affected, results, deleted, octopusBaseUrl, task.getId());
        boolean ok = safePostComment(repoConfig, task.getChangeNumber(), body);
        if (!ok) {
            // 审查结果已持久化（review_results），只有评论回写失败 → 半完成态，
            // /rewrite 端点从 WRITEBACK 恢复且不再重跑 IMPACT/REVIEW
            return failPartial(task, "CALLBACK_FAILED",
                    "Review completed but Gerrit comment writeback failed; result retained in Octopus. "
                            + "Re-run via /rewrite to retry only the writeback.");
        }
        return complete(task);
    }

    // ── State helpers ──

    private ChangeReviewTask complete(ChangeReviewTask task) {
        task.setStatus("COMPLETED");
        task.setCurrentPhase(null);
        taskRepository.save(task);
        return task;
    }

    private ChangeReviewTask fail(ChangeReviewTask task, String failureType, String message) {
        task.setStatus("FAILED");
        task.setFailureType(failureType);
        task.setErrorMessage(message);
        taskRepository.save(task);
        return task;
    }

    private ChangeReviewTask failPartial(ChangeReviewTask task, String failureType, String message) {
        task.setStatus("PARTIALLY_COMPLETED");
        task.setFailureType(failureType);
        task.setErrorMessage(message);
        task.setCurrentPhase(PHASE_WRITEBACK); // /rewrite 由此恢复
        taskRepository.save(task);
        return task;
    }

    private void persistPhase(ChangeReviewTask task, String phase, String errorMessage) {
        task.setCurrentPhase(phase);
        if (errorMessage != null) task.setErrorMessage(errorMessage);
        taskRepository.save(task);
    }

    private ChangeReviewTask done(ChangeReviewTask task) {
        return task;
    }

    private boolean safePostComment(RepoConfig repoConfig, String change, String body) {
        try {
            return gerritRestClient.postComment(repoConfig, change, body);
        } catch (Exception e) {
            log.warn("Gerrit postComment error for change {}: {}", change, e.getMessage());
            return false;
        }
    }

    private String toJsonArray(List<String> ids) {
        JsonArray arr = new JsonArray();
        for (String id : ids) arr.add(id);
        return gson.toJson(arr);
    }

    /** Order phases; returns true if {@code a} comes before {@code b}. */
    private boolean indexBefore(String a, String b) {
        return phaseIndex(a) < phaseIndex(b);
    }

    private int phaseIndex(String phase) {
        return switch (phase) {
            case PHASE_FETCH_MERGE -> 0;
            case PHASE_ANALYZE -> 1;
            case PHASE_DETECT_CHANGE -> 2;
            case PHASE_IMPACT -> 3;
            case PHASE_REVIEW -> 4;
            case PHASE_WRITEBACK -> 5;
            default -> 0;
        };
    }
}
