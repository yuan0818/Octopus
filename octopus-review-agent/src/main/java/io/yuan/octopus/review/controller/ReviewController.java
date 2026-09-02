package io.yuan.octopus.review.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.review.dto.ChatRequest;
import io.yuan.octopus.review.dto.ReviewRequest;
import io.yuan.octopus.review.dto.ReviewResponse;
import io.yuan.octopus.review.service.ReviewChatService;
import io.yuan.octopus.review.service.ReviewOrchestrator;
import io.yuan.octopus.review.service.ReviewOrchestrator.ReviewPreview;
import io.yuan.octopus.review.service.ReviewOrchestrator.ReviewSessionStatus;
import io.yuan.octopus.review.service.ReviewOrchestrator.ReviewValidation;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);
    private static final Path UPLOAD_DIR = Path.of("/tmp/octopus/upload");

    private final ReviewOrchestrator orchestrator;
    private final ReviewChatService chatService;

    public ReviewController(ReviewOrchestrator orchestrator, ReviewChatService chatService) {
        this.orchestrator = orchestrator;
        this.chatService = chatService;
    }

    /**
     * 审查追问（混合形态 review 的对话层）：首轮结构化审查完成后，
     * 基于同一链路上下文继续提问，免 schema 校验，消息按 threadId 落库。
     */
    @PostMapping("/review/chat")
    public Result<ReviewChatService.ChatResult> chat(@Valid @RequestBody ChatRequest request) {
        try {
            return Result.success(chatService.chat(request));
        } catch (IllegalArgumentException e) {
            // 配置类错误（未注册模型等）返回 400 语义而非 500
            return Result.error(400, e.getMessage());
        }
    }

    /** 追问历史（刷新恢复用）。 */
    @GetMapping("/review/chat/{threadId}")
    public Result<List<Map<String, Object>>> chatHistory(@PathVariable String threadId) {
        return Result.success(chatService.history(threadId).stream()
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("role", m.getRole());
                    row.put("content", m.getContent());
                    row.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
                    return row;
                })
                .collect(Collectors.toList()));
    }

    /**
     * Upload file(s) for a review session.
     * Files are saved to {@code /tmp/octopus/upload/{projectId}/{timestamp}/}.
     * Returns saved file paths that can be used as {@code docPath} in review requests.
     * <p>
     * Example: {@code POST /api/review/upload?projectId=1}
     * Content-Type: multipart/form-data with field name "files"
     */
    @PostMapping("/review/upload")
    public Result<List<Map<String, Object>>> uploadFiles(
            @RequestParam(defaultValue = "0") long projectId,
            @RequestParam("files") List<MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            return Result.error(400, "No files provided");
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path sessionDir = UPLOAD_DIR.resolve(projectId + "_" + timestamp);

        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            log.error("Failed to create upload dir: {}", sessionDir, e);
            return Result.error(500, "Failed to create upload directory");
        }

        List<Map<String, Object>> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;

            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                originalName = "unnamed";
            }

            // Sanitize filename to prevent path traversal
            String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path targetPath = sessionDir.resolve(safeName);

            try {
                Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("originalName", originalName);
                entry.put("savedPath", targetPath.toString());
                entry.put("size", file.getSize());
                entry.put("dir", sessionDir.toString());
                saved.add(entry);
                log.info("Uploaded file: {} -> {} ({} bytes)", originalName, targetPath, file.getSize());
            } catch (IOException e) {
                log.error("Failed to save uploaded file: {}", originalName, e);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("originalName", originalName);
                entry.put("error", e.getMessage());
                saved.add(entry);
            }
        }

        return Result.success(saved);
    }

    /**
     * Start a code review.
     * SYNC mode: returns the review result directly (200).
     * ASYNC mode: returns a session ID immediately (202).
     */
    @PostMapping("/review")
    public Result<?> review(@Valid @RequestBody ReviewRequest request) {
        try {
            if ("ASYNC".equalsIgnoreCase(request.getMode())) {
                String sessionId = orchestrator.startAsync(request);
                return Result.success(Map.of("sessionId", sessionId, "status", "PENDING"));
            }

            ReviewResponse response = orchestrator.review(request);
            return Result.success(response);

        } catch (IllegalArgumentException e) {
            log.warn("Review validation failed: {}", e.getMessage());
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Review failed for {}.{}", request.getClassName(), request.getMethodName(), e);
            String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "unknown error");
            StackTraceElement[] stack = e.getStackTrace();
            if (stack.length > 0) {
                msg += " at " + stack[0];
                if (e.getCause() != null && e.getCause() != e) {
                    msg += " caused by " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
                }
            }
            return Result.error(500, "审查执行失败: " + msg);
        }
    }

    /**
     * Validate whether a review can be executed (checks config, not calling LLM).
     */
    @PostMapping("/review/validate")
    public Result<ReviewValidation> validate(@RequestBody ReviewRequest request) {
        try {
            ReviewValidation validation = orchestrator.validate(request);
            return Result.success(validation);
        } catch (Exception e) {
            log.error("Validate failed: {}", e.getMessage());
            return Result.error(500, "验证失败: " + e.getMessage());
        }
    }

    /**
     * Preview the assembled prompt without calling LLM.
     */
    @PostMapping("/review/preview")
    public Result<ReviewPreview> preview(@RequestBody ReviewRequest request) {
        try {
            ReviewPreview preview = orchestrator.preview(request);
            return Result.success(preview);
        } catch (Exception e) {
            return Result.error(500, "Preview failed: " + e.getMessage());
        }
    }

    /**
     * Get the result of an async review session.
     */
    @GetMapping("/review/{sessionId}")
    public Result<?> getReview(@PathVariable String sessionId) {
        try {
            ReviewSessionStatus status = orchestrator.getSessionStatus(sessionId);
            if (!"COMPLETED".equals(status.getStatus())) {
                return Result.success(Map.of(
                        "sessionId", sessionId,
                        "status", status.getStatus(),
                        "error", status.getError()
                ));
            }
            return Result.success(status.getResponse());
        } catch (NoSuchElementException e) {
            return Result.error(404, e.getMessage());
        }
    }
}
