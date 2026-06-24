package io.yuan.octopus.review.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.infra.memory.model.KnowledgeType;
import io.yuan.octopus.infra.memory.model.ProjectKnowledge;
import io.yuan.octopus.infra.memory.model.ReviewFinding;
import io.yuan.octopus.infra.memory.model.ReviewSession;
import io.yuan.octopus.infra.memory.service.MemoryManager;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryManager memoryManager;

    public MemoryController(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * Query active project knowledge.
     */
    @GetMapping("/knowledge")
    public Result<List<ProjectKnowledge>> getKnowledge(
            @RequestParam String projectId,
            @RequestParam(required = false) String type) {
        if (type != null) {
            try {
                KnowledgeType kt = KnowledgeType.valueOf(type.toUpperCase());
                return Result.success(memoryManager.getKnowledgeByType(projectId, kt));
            } catch (IllegalArgumentException e) {
                return Result.error(400, "Invalid knowledge type: " + type);
            }
        }
        return Result.success(memoryManager.getActiveKnowledge(projectId));
    }

    /**
     * Manually add project knowledge (for architects).
     */
    @PostMapping("/knowledge")
    public Result<?> addKnowledge(@RequestBody ProjectKnowledge knowledge) {
        if (knowledge.getProjectId() == null || knowledge.getTitle() == null) {
            return Result.error(400, "projectId and title are required");
        }
        knowledge.setSource("MANUAL");
        memoryManager.recordKnowledge(knowledge);
        return Result.success(Map.of("id", knowledge.getId()));
    }

    /**
     * Query review findings for a specific class.
     */
    @GetMapping("/findings")
    public Result<List<ReviewFinding>> getFindings(
            @RequestParam String projectId,
            @RequestParam(required = false) String className) {
        if (className != null) {
            return Result.success(memoryManager.getFindings(projectId, className));
        }
        return Result.success(List.of());
    }

    /**
     * List review sessions for a project (newest first).
     */
    @GetMapping("/sessions")
    public Result<List<ReviewSession>> getSessions(
            @RequestParam String projectId,
            @RequestParam(required = false) String anchorClass,
            @RequestParam(required = false) String anchorMethod) {
        if (anchorClass != null && anchorMethod != null) {
            return Result.success(memoryManager.getSessionsByAnchor(projectId, anchorClass, anchorMethod));
        }
        return Result.success(memoryManager.getSessions(projectId));
    }

    /**
     * Paginated review session history with filtering.
     */
    @GetMapping("/sessions/page")
    public Result<Map<String, Object>> getSessionsPaged(
            @RequestParam String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String anchorClass,
            @RequestParam(required = false) LocalDateTime startTime,
            @RequestParam(required = false) LocalDateTime endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReviewSession> result = memoryManager.getSessionsFiltered(
                projectId, status, anchorClass, startTime, endTime, page, size);
        return Result.success(Map.of(
                "content", result.getContent(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "page", result.getNumber(),
                "size", result.getSize()
        ));
    }

    /**
     * Get a single review session by ID (including full report).
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<?> getSessionDetail(@PathVariable String sessionId) {
        Optional<ReviewSession> session = memoryManager.getSessionById(sessionId);
        if (session.isEmpty()) {
            return Result.error(404, "Session not found: " + sessionId);
        }
        return Result.success(session.get());
    }

    /**
     * Get findings for a specific review session.
     */
    @GetMapping("/sessions/{sessionId}/findings")
    public Result<List<ReviewFinding>> getSessionFindings(@PathVariable String sessionId) {
        return Result.success(memoryManager.getFindingsBySession(sessionId));
    }
}
