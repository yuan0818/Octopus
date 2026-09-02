package io.yuan.octopus.infra.memory.service;

import io.yuan.octopus.infra.memory.model.KnowledgeType;
import io.yuan.octopus.infra.memory.model.ProjectKnowledge;
import io.yuan.octopus.infra.memory.model.ReviewFinding;
import io.yuan.octopus.infra.memory.model.ReviewSession;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 跨服务记忆管理器接口。
 * 所有项目知识和审查发现的读写均通过此接口。
 */
public interface MemoryManager {

    /**
     * 构建项目上下文字符串，直接可用于拼接 LLM Prompt。
     * 返回值示例：
     * "### 项目编码规范
     *  ★ [ESTABLISHED] 所有 Service 必须加 @Transactional
     *  ★ [CONFIRMED] 使用 @Slf4j 而非 LoggerFactory"
     */
    String buildProjectContext(String projectId, String targetClassName);

    // ── 审查发现 ──

    void recordFinding(String projectId, String anchorClass, String anchorMethod,
                       String sessionId, String requirementId, String requirementTitle,
                       Boolean passed, String severity, String category,
                       String findingText, String suggestion, String codeReferences);

    void recordSession(String sessionId, String projectId, String anchorClass,
                       String anchorMethod, String strategy, String requirements,
                       String chainClasses, String fullReport, String model,
                       int promptTokens, int completionTokens, int totalTokens,
                       int durationMs, String summary, String status,
                       String commitId, String tag);

    /**
     * 变更触发审查重载：在版本化字段之外关联 Change / patchset / change_review_task。
     * changeNumber / patchsetNumber / changeReviewTaskId 可为 null（既有非 change 触发的 review 走原签名）。
     */
    void recordSession(String sessionId, String projectId, String anchorClass,
                       String anchorMethod, String strategy, String requirements,
                       String chainClasses, String fullReport, String model,
                       int promptTokens, int completionTokens, int totalTokens,
                       int durationMs, String summary, String status,
                       String commitId, String tag,
                       String changeNumber, Integer patchsetNumber, Long changeReviewTaskId);

    List<ReviewFinding> getFindings(String projectId, String anchorClass);

    List<ReviewSession> getSessions(String projectId);

    List<ReviewSession> getSessionsByAnchor(String projectId, String anchorClass, String anchorMethod);

    Page<ReviewSession> getSessionsFiltered(String projectId, String status, String anchorClass,
                                             LocalDateTime startTime, LocalDateTime endTime,
                                             String commitId, int page, int size);

    Optional<ReviewSession> getSessionById(String sessionId);

    List<ReviewFinding> getFindingsBySession(String sessionId);

    /**
     * 按 change_review_task_id 查关联的 review session（变更触发审查详情用）。
     */
    List<ReviewSession> getSessionsByChangeTask(Long changeReviewTaskId);

    // ── 项目知识 ──

    void recordKnowledge(ProjectKnowledge knowledge);

    List<ProjectKnowledge> getActiveKnowledge(String projectId);

    List<ProjectKnowledge> getKnowledgeByType(String projectId, KnowledgeType type);
}
