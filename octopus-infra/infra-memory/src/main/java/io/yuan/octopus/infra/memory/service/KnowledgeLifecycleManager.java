package io.yuan.octopus.infra.memory.service;

import io.yuan.octopus.infra.memory.model.KnowledgeStatus;
import io.yuan.octopus.infra.memory.model.ProjectKnowledge;
import io.yuan.octopus.infra.memory.repository.ProjectKnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理项目知识的置信度和状态流转。
 *
 * 状态流:
 * TENTATIVE (confidence < confirmThreshold)
 *   → 命中 +1 → CONFIRMED (confidence >= confirmThreshold)
 *   → 命中 +1 → ESTABLISHED (confidence >= establishThreshold)
 *   → 反例 -1 → CONTROVERSIAL (confidence < 0)
 *   → 过期   → STALE
 */
@Component
public class KnowledgeLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLifecycleManager.class);

    private final ProjectKnowledgeRepository repository;

    private final int confirmThreshold;
    private final int establishThreshold;

    public KnowledgeLifecycleManager(ProjectKnowledgeRepository repository,
                                     @Value("${memory.confidence.confirm-threshold:3}") int confirmThreshold,
                                     @Value("${memory.confidence.establish-threshold:5}") int establishThreshold) {
        this.repository = repository;
        this.confirmThreshold = confirmThreshold;
        this.establishThreshold = establishThreshold;
    }

    /**
     * 知识被印证时调用：confidence + 1，达到阈值则升级状态。
     */
    public void onHit(Long knowledgeId) {
        ProjectKnowledge k = repository.findById(knowledgeId).orElse(null);
        if (k == null) {
            log.warn("Knowledge not found: {}", knowledgeId);
            return;
        }
        k.setConfidence(k.getConfidence() + 1);
        k.setLastVerifiedAt(LocalDateTime.now());

        if (k.getConfidence() >= establishThreshold) {
            k.setStatus(KnowledgeStatus.ESTABLISHED);
        } else if (k.getConfidence() >= confirmThreshold) {
            k.setStatus(KnowledgeStatus.CONFIRMED);
        }

        repository.save(k);
        log.info("Knowledge #{} confidence={}, status={}", knowledgeId, k.getConfidence(), k.getStatus());
    }

    /**
     * 知识出现反例时调用：confidence - 1，低于 0 则标记争议。
     */
    public void onCounterExample(Long knowledgeId, String sessionId, String fileLocation) {
        ProjectKnowledge k = repository.findById(knowledgeId).orElse(null);
        if (k == null) {
            log.warn("Knowledge not found: {}", knowledgeId);
            return;
        }
        k.setConfidence(k.getConfidence() - 1);

        // Append counter example to existing JSON (simple append for now)
        String existing = k.getCounterExamples();
        String newEntry = String.format(
                "{\"sessionId\":\"%s\",\"file\":\"%s\",\"observedAt\":\"%s\"}",
                sessionId, fileLocation, LocalDateTime.now());
        if (existing == null || existing.isBlank() || existing.equals("[]")) {
            k.setCounterExamples("[" + newEntry + "]");
        } else {
            k.setCounterExamples(existing.substring(0, existing.length() - 1) + "," + newEntry + "]");
        }

        if (k.getConfidence() < 0) {
            k.setStatus(KnowledgeStatus.CONTROVERSIAL);
        }

        repository.save(k);
        log.warn("Knowledge #{} counter-example added, confidence={}, status={}",
                knowledgeId, k.getConfidence(), k.getStatus());
    }

    /**
     * 标记知识为过期。
     */
    public void markStale(Long knowledgeId, String reason) {
        ProjectKnowledge k = repository.findById(knowledgeId).orElse(null);
        if (k == null) {
            log.warn("Knowledge not found: {}", knowledgeId);
            return;
        }
        k.setStatus(KnowledgeStatus.STALE);
        k.setContent(reason);
        repository.save(k);
        log.info("Knowledge #{} marked STALE: {}", knowledgeId, reason);
    }

    /**
     * 获取超过指定天数未验证的知识，待重新审查确认。
     */
    public List<ProjectKnowledge> getExpiredKnowledge(int olderThanDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(olderThanDays);
        return repository.findByLastVerifiedAtBefore(cutoff);
    }

    /**
     * 获取需要人工确认的争议知识。
     */
    public List<ProjectKnowledge> getPendingReview(String projectId) {
        return repository.findByProjectIdAndStatus(projectId, KnowledgeStatus.CONTROVERSIAL);
    }
}
