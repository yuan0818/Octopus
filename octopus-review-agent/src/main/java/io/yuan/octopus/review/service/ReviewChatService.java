package io.yuan.octopus.review.service;

import io.yuan.octopus.infra.memory.service.MemoryManager;
import io.yuan.octopus.review.adapter.LLMAdapter.LLMResponse;
import io.yuan.octopus.review.dto.ChatRequest;
import io.yuan.octopus.review.dto.LLMConfig;
import io.yuan.octopus.review.entity.ReviewChatMessage;
import io.yuan.octopus.review.model.ModelConfig;
import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.repository.ReviewChatMessageRepository;
import io.yuan.octopus.review.skill.Skill;
import io.yuan.octopus.review.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审查追问对话（混合形态 review 的对话层）。
 *
 * <p>首轮仍是结构化审查（schema 校验的 issue 产出，走 ReviewOrchestrator）；
 * 本服务只处理完成后的追问：复用同一链路的源码/项目上下文 + 前端传入的
 * 审查结论，免 schema 校验的自由问答，消息按 threadId 落库支持刷新恢复。
 */
@Service
public class ReviewChatService {

    private static final Logger log = LoggerFactory.getLogger(ReviewChatService.class);

    /** 拼入 prompt 的最大历史轮数（控制上下文体积）。 */
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final LLMIntegrationService llmIntegrationService;
    private final ModelRegistry modelRegistry;
    private final SkillRegistry skillRegistry;
    private final CodeRetrievalService codeRetrieval;
    private final MemoryManager memoryManager;
    private final ReviewChatMessageRepository chatRepository;

    public ReviewChatService(LLMIntegrationService llmIntegrationService,
                             ModelRegistry modelRegistry,
                             SkillRegistry skillRegistry,
                             CodeRetrievalService codeRetrieval,
                             MemoryManager memoryManager,
                             ReviewChatMessageRepository chatRepository) {
        this.llmIntegrationService = llmIntegrationService;
        this.modelRegistry = modelRegistry;
        this.skillRegistry = skillRegistry;
        this.codeRetrieval = codeRetrieval;
        this.memoryManager = memoryManager;
        this.chatRepository = chatRepository;
    }

    public record ChatResult(String threadId, String answer, String model) {}

    public ChatResult chat(ChatRequest request) {
        String threadId = request.getThreadId() != null && !request.getThreadId().isBlank()
                ? request.getThreadId()
                : "chat-" + UUID.randomUUID().toString().substring(0, 8);

        List<ReviewChatMessage> recent = chatRepository
                .findTop20ByThreadIdOrderByCreatedAtDescIdDesc(threadId);
        Collections.reverse(recent);

        String userPrompt = buildUserPrompt(request, recent);
        LLMConfig config = resolveLLMConfig(request);
        LLMResponse response = llmIntegrationService.call(systemPrompt(), userPrompt, config);

        chatRepository.save(message(threadId, "USER", request.getQuestion()));
        chatRepository.save(message(threadId, "ASSISTANT", response.content()));
        log.info("Chat turn answered on thread {} ({} chars)", threadId, response.content().length());

        return new ChatResult(threadId, response.content(), response.model());
    }

    public List<ReviewChatMessage> history(String threadId) {
        return chatRepository.findByThreadIdOrderByCreatedAtAscIdAsc(threadId);
    }

    // ---------------------------------------------------------------------

    private String systemPrompt() {
        return """
                你是代码审查追问助手。用户刚完成一次入口链路审查，会基于该审查的链路源码和结论继续提问。

                规则：
                - 只依据提供的链路源码、审查结论和追问历史回答，不要臆造上下文中不存在的代码。
                - 引用代码时给出符号（类#方法）并引用原文；代码语言以源码本身为准。
                - 问题超出提供上下文（需要链路之外的代码或全局信息）时，明确说明缺少什么，再给条件性结论。
                - 回复先直接回答、再给依据；给代码建议时输出可执行片段，不要泛泛建议。
                """;
    }

    private String buildUserPrompt(ChatRequest request, List<ReviewChatMessage> recent) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 审查链路\n");
        sb.append(request.getClassName() != null ? request.getClassName() : "unknown");
        sb.append("#").append(request.getMethodName() != null ? request.getMethodName() : "unknown");
        if (request.getBranch() != null) {
            sb.append(" (branch=").append(request.getBranch());
            if (request.getCommitId() != null) {
                sb.append(", commit=").append(request.getCommitId().substring(0, Math.min(12, request.getCommitId().length())));
            }
            sb.append(")");
        }
        sb.append("\n\n");

        // 链路源码：复用审查时的取数路径（best-effort，取不到不阻断追问）
        String sources = retrieveSources(request);
        sb.append("## 链路源码\n").append(sources).append("\n\n");

        if (request.getReviewResult() != null && !request.getReviewResult().isBlank()) {
            sb.append("## 审查结论\n").append(request.getReviewResult().strip()).append("\n\n");
        }
        if (request.getDiffContext() != null && !request.getDiffContext().isBlank()) {
            sb.append("## Diff Context（链路版本对比）\n").append(request.getDiffContext().strip()).append("\n\n");
        }

        if (!recent.isEmpty()) {
            sb.append("## 历史追问\n");
            recent.stream()
                    .limit(MAX_HISTORY_MESSAGES)
                    .forEach(m -> sb.append(m.getRole().equals("USER") ? "Q: " : "A: ")
                            .append(abbreviate(m.getContent(), 4000)).append("\n"));
            sb.append("\n");
        }

        sb.append("## 问题\n").append(request.getQuestion().strip());
        return sb.toString();
    }

    private String retrieveSources(ChatRequest request) {
        if (request.getProjectId() == null || request.getClassName() == null || request.getMethodName() == null) {
            return "（未提供链路定位信息）";
        }
        try {
            Map<String, String> source = codeRetrieval.getSourceCode(
                    request.getProjectId(), request.getClassName(), request.getMethodName(),
                    request.getStrategy(), request.getBranch(), request.getCommitId());
            if (source == null || source.isEmpty()) {
                return "（链路源码不可得——源码还原服务可能不可用）";
            }
            StringBuilder sb = new StringBuilder();
            source.forEach((cls, code) -> sb.append("--- ").append(cls).append(" ---\n")
                    .append(abbreviate(code, 8000)).append("\n\n"));
            return sb.toString();
        } catch (Exception e) {
            log.warn("Chat source retrieval failed, continuing without sources: {}", e.getMessage());
            return "（链路源码获取失败：源码还原服务不可用，请基于审查结论回答）";
        }
    }

    private LLMConfig resolveLLMConfig(ChatRequest request) {
        ModelConfig modelConfig = null;
        if (request.getModelName() != null && !request.getModelName().isBlank()) {
            modelConfig = modelRegistry.get(request.getModelName());
        }
        if (modelConfig == null && request.getSkillId() != null && !request.getSkillId().isBlank()) {
            try {
                Skill skill = skillRegistry.resolve(request.getSkillId());
                if (skill.getPreferredProvider() != null) {
                    modelConfig = modelRegistry.select(skill.getPreferredProvider(), "general");
                }
            } catch (IllegalArgumentException ignored) {
                // skill 不存在时直接走兜底
            }
        }
        if (modelConfig == null) {
            modelConfig = modelRegistry.selectWithFallback("general");
        }
        if (modelConfig == null) {
            throw new IllegalArgumentException("No model configured. Register one in Model Management first.");
        }
        LLMConfig config = new LLMConfig();
        config.setProvider(modelConfig.getProvider());
        config.setApiFormat(modelConfig.getApiFormat());
        config.setModel(modelConfig.getModelId());
        config.setApiKey(modelConfig.getApiKey());
        config.setApiUrl(modelConfig.getApiUrl());
        config.setMaxTokens(Math.min(modelConfig.getMaxTokens(), 8000));
        config.setTemperature(modelConfig.getTemperature());
        return config;
    }

    private ReviewChatMessage message(String threadId, String role, String content) {
        ReviewChatMessage m = new ReviewChatMessage();
        m.setThreadId(threadId);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…(截断)";
    }
}
