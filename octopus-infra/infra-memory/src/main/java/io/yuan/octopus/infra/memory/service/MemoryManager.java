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

    List<ReviewFinding> getFindings(String projectId, String anchorClass);

    List<ReviewSession> getSessions(String projectId);

    List<ReviewSession> getSessionsByAnchor(String projectId, String anchorClass, String anchorMethod);

    Page<ReviewSession> getSessionsFiltered(String projectId, String status, String anchorClass,
                                             LocalDateTime startTime, LocalDateTime endTime,
                                             int page, int size);

    Optional<ReviewSession> getSessionById(String sessionId);

    List<ReviewFinding> getFindingsBySession(String sessionId);

    // ── 项目知识 ──

    void recordKnowledge(ProjectKnowledge knowledge);

    List<ProjectKnowledge> getActiveKnowledge(String projectId);

    List<ProjectKnowledge> getKnowledgeByType(String projectId, KnowledgeType type);
}
