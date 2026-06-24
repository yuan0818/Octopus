package io.yuan.octopus.review.service;

import com.google.gson.Gson;
import io.yuan.octopus.infra.memory.service.MemoryManager;
import io.yuan.octopus.review.dto.*;
import io.yuan.octopus.review.model.ModelConfig;
import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.parsing.RequirementParser;
import io.yuan.octopus.review.prompt.PromptManager;
import io.yuan.octopus.review.skill.Skill;
import io.yuan.octopus.review.skill.SkillContext;
import io.yuan.octopus.review.skill.SkillExecutor;
import io.yuan.octopus.review.skill.SkillRegistry;
import io.yuan.octopus.review.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the full review pipeline:
 * 1. Get project knowledge → 2. Get chain metadata → 3. Get source code
 * 4. Parse requirements → 5. Build prompt → 6. Call LLM
 * 7. Parse result → 8. Store findings → 9. Extract project patterns
 */
@Component
public class ReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final MemoryManager memoryManager;
    private final CodeRetrievalService codeRetrieval;
    private final RequirementParser requirementParser;
    private final PromptBuilder promptBuilder;
    private final LLMIntegrationService llmIntegration;
    private final ReviewResultParser reviewResultParser;
    private final ProjectPatternExtractor patternExtractor;
    private final SkillRegistry skillRegistry;
    private final SkillExecutor skillExecutor;
    private final PromptManager promptManager;
    private final ModelRegistry modelRegistry;
    private final Gson gson = new Gson();

    // In-memory session store for async mode
    private final ConcurrentHashMap<String, ReviewSessionStatus> sessionStore = new ConcurrentHashMap<>();

    public ReviewOrchestrator(MemoryManager memoryManager,
                              CodeRetrievalService codeRetrieval,
                              RequirementParser requirementParser,
                              PromptBuilder promptBuilder,
                              LLMIntegrationService llmIntegration,
                              ReviewResultParser reviewResultParser,
                              ProjectPatternExtractor patternExtractor,
                              SkillRegistry skillRegistry,
                              SkillExecutor skillExecutor,
                              PromptManager promptManager,
                              ModelRegistry modelRegistry) {
        this.memoryManager = memoryManager;
        this.codeRetrieval = codeRetrieval;
        this.requirementParser = requirementParser;
        this.promptBuilder = promptBuilder;
        this.llmIntegration = llmIntegration;
        this.reviewResultParser = reviewResultParser;
        this.patternExtractor = patternExtractor;
        this.skillRegistry = skillRegistry;
        this.skillExecutor = skillExecutor;
        this.promptManager = promptManager;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Synchronous review — returns result directly.
     */
    public ReviewResponse review(ReviewRequest request) {
        return executeReview(request, null);
    }

    /**
     * Async review — returns a pending session ID immediately.
     */
    public String startAsync(ReviewRequest request) {
        String sessionId = "rev_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + UUID.randomUUID().toString().substring(0, 8);

        ReviewSessionStatus status = new ReviewSessionStatus(sessionId, "PENDING", null, null);
        sessionStore.put(sessionId, status);

        CompletableFuture.runAsync(() -> {
            try {
                status.status = "IN_PROGRESS";
                ReviewResponse response = executeReview(request, sessionId);
                status.status = "COMPLETED";
                status.response = response;
            } catch (Exception e) {
                status.status = "FAILED";
                status.error = e.getMessage();
                log.error("Async review failed: {}", e.getMessage());
            }
        });

        return sessionId;
    }

    /**
     * Get async review session status.
     */
    public ReviewSessionStatus getSessionStatus(String sessionId) {
        ReviewSessionStatus status = sessionStore.get(sessionId);
        if (status == null) {
            throw new NoSuchElementException("Session not found: " + sessionId);
        }
        return status;
    }

    /**
     * Preview the review — assembles prompt and returns metadata without calling LLM.
     */
    public ReviewPreview preview(ReviewRequest request) {
        String pid = String.valueOf(request.getProjectId());
        ReviewMode reviewMode = ReviewMode.infer(request);
        Skill skill = skillRegistry.resolve(request.getSkillId());
        log.info("Preview for {}.{} using skill '{}' in {} mode",
                request.getClassName(), request.getMethodName(), skill.getId(), reviewMode);

        // 1. Project knowledge
        String projectContext = memoryManager.buildProjectContext(pid, request.getClassName());

        // 2. Chain metadata
        Map<String, Object> chainMetadata = codeRetrieval.getChainMetadata(
                request.getProjectId(), request.getClassName(), request.getMethodName(), request.getStrategy());

        // 3. Source code
        Map<String, String> sourceCode = codeRetrieval.getSourceCode(
                request.getProjectId(), request.getClassName(), request.getMethodName(), request.getStrategy());

        // 4. Parse requirements
        List<RequirementItem> requirements = requirementParser.parse(request);

        // 5. Build prompt
        String systemPrompt = promptBuilder.getSystemPrompt(skill);
        String userPrompt = promptBuilder.build(skill, projectContext, chainMetadata, requirements, sourceCode, reviewMode);

        // Estimate tokens (rough: ~4 chars per token)
        int estimatedTokens = (systemPrompt.length() + userPrompt.length()) / 4;

        return new ReviewPreview(
                skill.getId(),
                skill.getName(),
                skill.getPreferredProvider(),
                sourceCode.size(),
                chainMetadata.get("totalSignatures") instanceof Number n ? n.intValue() : 0,
                estimatedTokens,
                systemPrompt.length() + userPrompt.length(),
                systemPrompt.substring(0, Math.min(200, systemPrompt.length()))
        );
    }

    /**
     * Validate whether a review can be executed for the given request.
     */
    public ReviewValidation validate(ReviewRequest request) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Check Skill
        Skill skill = skillRegistry.resolve(request.getSkillId());
        if (skill == null) {
            errors.add("No skill available. Create one in Agent Config first.");
            return new ReviewValidation(false, errors, warnings);
        }

        // Check model availability
        String provider = skill.getPreferredProvider();
        ModelConfig modelConfig = modelRegistry.select(provider, "general");
        if (modelConfig == null) {
            errors.add("No model configured for provider '" + provider + "'. Register a model first.");
        }

        // Check API key
        if (modelConfig != null) {
            String apiKey = modelRegistry.getApiKey(provider);
            if (apiKey == null || apiKey.isBlank()) {
                errors.add("API key for '" + provider + "' is not configured.");
            } else {
                warnings.add("Using " + modelConfig.getModelId() + " via " + provider);
            }
        }

        // Check requirements / review mode
        ReviewMode reviewMode = ReviewMode.infer(request);
        if ((request.getRequirements() == null || request.getRequirements().isEmpty())
                && (request.getDocPath() == null || request.getDocPath().isBlank())) {
            warnings.add("No requirements provided; review will run in RISK_BASED mode and only report evidence-backed chain risks.");
        }
        if (reviewMode == ReviewMode.RISK_BASED
                && ((request.getRequirements() != null && !request.getRequirements().isEmpty())
                || (request.getDocPath() != null && !request.getDocPath().isBlank()))) {
            warnings.add("reviewMode=RISK_BASED ignores requirement coverage checks even though requirement input is present.");
        }

        // Check entry
        if (request.getClassName() == null || request.getMethodName() == null) {
            errors.add("className and methodName are required.");
        }

        return new ReviewValidation(errors.isEmpty(), errors, warnings);
    }

    // ── Internal execution ──

    private ReviewResponse executeReview(ReviewRequest request, String sessionId) {
        if (sessionId == null) {
            sessionId = "rev_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + "_" + UUID.randomUUID().toString().substring(0, 8);
        }

        String pid = String.valueOf(request.getProjectId());
        ReviewMode reviewMode = ReviewMode.infer(request);
        long start = System.currentTimeMillis();

        // 1. Project knowledge
        String projectContext = memoryManager.buildProjectContext(pid, request.getClassName());

        // 2. Chain metadata
        log.info("[{}] Getting chain metadata for {}.{}", sessionId, request.getClassName(), request.getMethodName());
        Map<String, Object> chainMetadata = codeRetrieval.getChainMetadata(
                request.getProjectId(), request.getClassName(), request.getMethodName(), request.getStrategy());

        // 3. Source code
        log.info("[{}] Getting source code...", sessionId);
        Map<String, String> sourceCode = codeRetrieval.getSourceCode(
                request.getProjectId(), request.getClassName(), request.getMethodName(), request.getStrategy());

        // 4. Parse requirements
        List<RequirementItem> requirements = requirementParser.parse(request);

        // 5. Delegate prompt + LLM + parsing to SkillExecutor
        Skill skill = skillRegistry.resolve(request.getSkillId());
        log.info("[{}] Executing skill '{}' in {} mode ({} requirements, {} source classes)...",
                sessionId, skill.getId(), reviewMode, requirements.size(), sourceCode.size());

        LLMConfig llmConfig = resolveLLMConfig(request, skill);

        SkillContext skillContext = new SkillContext(
                pid, request.getClassName(), request.getMethodName(),
                request.getStrategy(), requirements,
                projectContext, chainMetadata, sourceCode, llmConfig, reviewMode);

        SkillResult skillResult = skillExecutor.execute(skill, skillContext);
        if (!skillResult.success()) {
            throw new RuntimeException("Skill execution failed: " + skillResult.errorMessage());
        }

        // 7. Extract ReviewResponse from SkillResult
        ReviewResponse reviewResponse = skillResult.reviewResponse();
        if (reviewResponse == null) {
            throw new RuntimeException("Skill returned no review response");
        }
        reviewResponse.setSessionId(sessionId);
        reviewResponse.setReviewMode(reviewMode);

        long elapsed = System.currentTimeMillis() - start;
        reviewResponse.setInvolvedClasses(sourceCode.size());
        reviewResponse.setTotalMethods(
                chainMetadata.get("totalSignatures") instanceof Number n ? n.intValue() : 0);

        // Build LLMResponse info for session recording
        int promptTokens = skillResult.promptTokens();
        int completionTokens = skillResult.completionTokens();
        String modelUsed = llmConfig.getProvider() + "/" + llmConfig.getModel();

        // 8. Store findings
        log.info("[{}] Storing findings...", sessionId);
        String chainClassesJson = gson.toJson(chainMetadata.get("involvedClasses"));
        String reportJson = gson.toJson(reviewResponse);
        String requirementsJson = gson.toJson(requirements);

        memoryManager.recordSession(sessionId, pid,
                request.getClassName(), request.getMethodName(),
                request.getStrategy(), requirementsJson, chainClassesJson,
                reportJson, modelUsed,
                promptTokens, completionTokens,
                promptTokens + completionTokens,
                (int) elapsed, reviewResponse.getSummary() != null
                        ? reviewResponse.getSummary().getPassed() + "/" + reviewResponse.getSummary().getTotalRequirements() + " passed"
                        : "",
                "COMPLETED",
                request.getCommitId(), request.getTag());

        // Store individual findings
        if (reviewResponse.getRequirementChecks() != null) {
            for (RequirementCheck check : reviewResponse.getRequirementChecks()) {
                memoryManager.recordFinding(pid, request.getClassName(), request.getMethodName(),
                        sessionId, check.getRequirementId(), check.getRequirementTitle(),
                        check.isPassed(), "INFO", "CONVENTION",
                        check.getDetail(), check.getSuggestion(),
                        gson.toJson(check.getCodeReferences()));
            }
        }
        if (reviewResponse.getIssues() != null) {
            for (CodeIssue issue : reviewResponse.getIssues()) {
                memoryManager.recordFinding(pid, request.getClassName(), request.getMethodName(),
                        sessionId, null, null, null,
                        issue.getSeverity(), issue.getCategory(),
                        issue.getDescription(), issue.getSuggestion(),
                        gson.toJson(issue.getCodeReferences()));
            }
        }

        // 9. Extract project patterns (async)
        String finalSessionId = sessionId;
        CompletableFuture.runAsync(() -> {
            try {
                patternExtractor.extract(pid, sourceCode, reviewResponse, llmConfig);
            } catch (Exception e) {
                log.warn("[{}] Pattern extraction failed: {}", finalSessionId, e.getMessage());
            }
        });

        log.info("[{}] Review complete in {}ms ({}+{} tokens)",
                sessionId, elapsed, promptTokens, completionTokens);
        return reviewResponse;
    }

    // ── Resolve LLM config from DB ──

    private LLMConfig resolveLLMConfig(ReviewRequest request, Skill skill) {
        // If caller provided explicit llmConfig, use it as base
        LLMConfig config = request.getLlmConfig() != null ? request.getLlmConfig() : new LLMConfig();

        // Resolve model from DB by modelName
        ModelConfig modelConfig = null;
        if (request.getModelName() != null && !request.getModelName().isBlank()) {
            modelConfig = modelRegistry.get(request.getModelName());
            if (modelConfig == null) {
                log.warn("Model '{}' not found in registry, falling back", request.getModelName());
            }
        }

        // Fallback: resolve by skill's preferred provider
        if (modelConfig == null && skill.getPreferredProvider() != null) {
            modelConfig = modelRegistry.select(skill.getPreferredProvider(), "general");
            if (modelConfig == null) {
                modelConfig = modelRegistry.selectWithFallback(skill.getPreferredProvider());
            }
        }

        // Last resort: any enabled model
        if (modelConfig == null) {
            for (ModelConfig mc : modelRegistry.getAllConfigs().values()) {
                if (mc.isEnabled()) { modelConfig = mc; break; }
            }
        }

        if (modelConfig == null) {
            throw new IllegalArgumentException("没有可用的模型配置，请在 Agent 配置 > 模型管理 中添加模型");
        }

        // Populate config from DB model
        config.setProvider(modelConfig.getProvider());
        config.setModel(modelConfig.getModelId());
        config.setApiKey(modelConfig.getApiKey());
        config.setApiUrl(modelConfig.getApiUrl());
        if (config.getMaxTokens() == null || config.getMaxTokens() <= 0) {
            config.setMaxTokens(modelConfig.getMaxTokens());
        }
        if (config.getTemperature() == null) {
            config.setTemperature(modelConfig.getTemperature());
        }

        log.info("Resolved model: {} (provider={}, modelId={})", modelConfig.getName(), modelConfig.getProvider(), modelConfig.getModelId());
        return config;
    }

    // ── Preview data (assembled prompt info, no LLM call) ──

    public record ReviewPreview(
            String skillId,
            String skillName,
            String provider,
            int classCount,
            int methodCount,
            int estimatedTokens,
            int totalChars,
            String systemPromptPreview
    ) {}

    // ── Validation result ──

    public record ReviewValidation(
            boolean valid,
            List<String> errors,
            List<String> warnings
    ) {}

    // ── Session status (for async mode) ──

    public static class ReviewSessionStatus {
        private final String sessionId;
        private String status;
        private ReviewResponse response;
        private String error;

        public ReviewSessionStatus(String sessionId, String status,
                                    ReviewResponse response, String error) {
            this.sessionId = sessionId;
            this.status = status;
            this.response = response;
            this.error = error;
        }

        public String getSessionId() { return sessionId; }
        public String getStatus() { return status; }
        public ReviewResponse getResponse() { return response; }
        public String getError() { return error; }
    }
}
