package io.yuan.octopus.review.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.yuan.octopus.infra.memory.model.KnowledgeStatus;
import io.yuan.octopus.infra.memory.model.KnowledgeType;
import io.yuan.octopus.infra.memory.model.ProjectKnowledge;
import io.yuan.octopus.infra.memory.service.MemoryManager;
import io.yuan.octopus.review.service.PromptBuilder;
import io.yuan.octopus.review.skill.Skill;
import io.yuan.octopus.review.skill.SkillRegistry;
import io.yuan.octopus.review.dto.LLMConfig;
import io.yuan.octopus.review.dto.ReviewResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * After a review completes, this component asks the LLM to extract
 * project-level patterns (coding conventions, architecture decisions, etc.)
 * and writes them as TENTATIVE knowledge.
 */
@Component
public class ProjectPatternExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProjectPatternExtractor.class);

    private final MemoryManager memoryManager;
    private final LLMIntegrationService llmIntegration;
    private final SkillRegistry skillRegistry;
    private final PromptBuilder promptBuilder;
    private final Gson gson = new Gson();

    public ProjectPatternExtractor(MemoryManager memoryManager,
                                    LLMIntegrationService llmIntegration,
                                    SkillRegistry skillRegistry,
                                    PromptBuilder promptBuilder) {
        this.memoryManager = memoryManager;
        this.llmIntegration = llmIntegration;
        this.skillRegistry = skillRegistry;
        this.promptBuilder = promptBuilder;
    }

    /**
     * Extract project-level patterns from the reviewed code.
     * Patterns are written as TENTATIVE knowledge to the memory store.
     */
    public void extract(String projectId, Map<String, String> sourceCode,
                         ReviewResponse response, LLMConfig llmConfig) {
        log.info("[{}] Extracting project patterns...", projectId);

        // Use skill-based prompt
        Skill skill = skillRegistry.resolve("pattern-extract");
        String systemPrompt = promptBuilder.getSystemPrompt(skill);

        // Build source code section (first 100 lines per class)
        StringBuilder sourceText = new StringBuilder();
        sourceText.append("## Source Code\n");
        for (var entry : sourceCode.entrySet()) {
            sourceText.append("--- ").append(entry.getKey()).append(".java ---\n");
            String[] lines = entry.getValue().split("\n");
            int limit = Math.min(lines.length, 100);
            for (int i = 0; i < limit; i++) {
                sourceText.append(lines[i]).append("\n");
            }
            if (lines.length > limit) {
                sourceText.append("// ... (").append(lines.length - limit).append(" more lines)\n");
            }
            sourceText.append("\n");
        }

        try {
            String result = llmIntegration.call(
                    systemPrompt,
                    sourceText.toString(),
                    llmConfig != null ? llmConfig : new LLMConfig()).content();

            JsonObject root = extractJson(result);
            JsonArray patterns = root.getAsJsonArray("patterns");
            if (patterns == null || patterns.isEmpty()) {
                log.info("No patterns extracted for project {}", projectId);
                return;
            }

            int saved = 0;
            int upgraded = 0;
            for (var elem : patterns) {
                JsonObject pattern = elem.getAsJsonObject();
                String title = pattern.get("title").getAsString();
                String type = pattern.get("type").getAsString();
                String detail = pattern.has("description") ? pattern.get("description").getAsString() : "";
                String confidence = pattern.has("confidence") ? pattern.get("confidence").getAsString() : "LOW";

                // Look for existing knowledge with the same title
                var existing = memoryManager.getActiveKnowledge(projectId).stream()
                        .filter(k -> k.getTitle().equals(title))
                        .findFirst();

                if (existing.isPresent()) {
                    ProjectKnowledge known = existing.get();
                    if (known.getStatus() == KnowledgeStatus.ESTABLISHED) {
                        // Already at highest confidence, skip
                        log.debug("Skipping established pattern: {}", title);
                        continue;
                    }
                    // Increment confidence
                    int newConfidence = known.getConfidence() + 1;
                    known.setConfidence(newConfidence);

                    // Upgrade status based on confidence thresholds
                    if (known.getStatus() == KnowledgeStatus.TENTATIVE && newConfidence >= 3) {
                        known.setStatus(KnowledgeStatus.CONFIRMED);
                        upgraded++;
                        log.info("Pattern upgraded to CONFIRMED: {} (confidence={})", title, newConfidence);
                    } else if (known.getStatus() == KnowledgeStatus.CONFIRMED && newConfidence >= 5) {
                        known.setStatus(KnowledgeStatus.ESTABLISHED);
                        upgraded++;
                        log.info("Pattern upgraded to ESTABLISHED: {} (confidence={})", title, newConfidence);
                    }
                    memoryManager.recordKnowledge(known);
                    saved++;
                } else {
                    // New pattern
                    ProjectKnowledge knowledge = new ProjectKnowledge();
                    knowledge.setProjectId(projectId);
                    knowledge.setTitle(title);
                    knowledge.setContent(detail);
                    knowledge.setSource("REVIEW");
                    knowledge.setKnowledgeType(mapPatternType(type));
                    memoryManager.recordKnowledge(knowledge);
                    saved++;
                }
            }

            log.info("Extracted {} patterns ({} upgraded) for project {}", saved, upgraded, projectId);

        } catch (Exception e) {
            log.warn("Pattern extraction failed: {}", e.getMessage());
        }
    }

    private KnowledgeType mapPatternType(String type) {
        return switch (type.toUpperCase()) {
            case "NAMING" -> KnowledgeType.NAMING_CONVENTION;
            case "ANNOTATION" -> KnowledgeType.CODING_CONVENTION;
            case "ERROR_HANDLING" -> KnowledgeType.DESIGN_PATTERN;
            case "LOGGING" -> KnowledgeType.CROSS_CUTTING;
            case "LAYERING", "ARCHITECTURE" -> KnowledgeType.ARCHITECTURE;
            case "ISSUE" -> KnowledgeType.KNOWN_ISSUE;
            case "DESIGN" -> KnowledgeType.DESIGN_PATTERN;
            case "CONVENTION" -> KnowledgeType.CODING_CONVENTION;
            default -> KnowledgeType.CODING_CONVENTION;
        };
    }

    private JsonObject extractJson(String content) {
        content = content.trim();

        // Strip markdown code fences if present
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            if (firstNewline > 0) {
                content = content.substring(firstNewline + 1);
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3);
            }
            content = content.trim();
        }

        int start = content.indexOf('{');
        if (start >= 0) {
            int depth = 0;
            for (int i = start; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return gson.fromJson(content.substring(start, i + 1), JsonObject.class);
                    }
                }
            }
        }
        throw new RuntimeException("No JSON in pattern extraction response");
    }
}
