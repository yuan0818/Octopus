package io.yuan.octopus.infra.memory.service;

import io.yuan.octopus.infra.memory.model.KnowledgeStatus;
import io.yuan.octopus.infra.memory.model.KnowledgeType;
import io.yuan.octopus.infra.memory.model.ProjectKnowledge;
import io.yuan.octopus.infra.memory.model.ReviewFinding;
import io.yuan.octopus.infra.memory.model.ReviewSession;
import io.yuan.octopus.infra.memory.repository.ProjectKnowledgeRepository;
import io.yuan.octopus.infra.memory.repository.ReviewFindingRepository;
import io.yuan.octopus.infra.memory.repository.ReviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MemoryManagerImpl implements MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManagerImpl.class);

    private final ProjectKnowledgeRepository knowledgeRepo;
    private final ReviewFindingRepository findingRepo;
    private final ReviewSessionRepository sessionRepo;

    public MemoryManagerImpl(ProjectKnowledgeRepository knowledgeRepo,
                             ReviewFindingRepository findingRepo,
                             ReviewSessionRepository sessionRepo) {
        this.knowledgeRepo = knowledgeRepo;
        this.findingRepo = findingRepo;
        this.sessionRepo = sessionRepo;
    }

    @Override
    public String buildProjectContext(String projectId, String targetClassName) {
        List<ProjectKnowledge> active = knowledgeRepo.findByProjectIdAndStatusIn(
                projectId,
                List.of(KnowledgeStatus.ESTABLISHED, KnowledgeStatus.CONFIRMED));

        if (active.isEmpty()) {
            return "";
        }

        return active.stream()
                .map(k -> {
                    String badge = switch (k.getStatus()) {
                        case ESTABLISHED -> "★ [ESTABLISHED]";
                        case CONFIRMED -> "● [CONFIRMED]";
                        case TENTATIVE -> "○ [TENTATIVE]";
                        default -> "  [" + k.getStatus() + "]";
                    };
                    return " " + badge + " " + k.getTitle();
                })
                .collect(Collectors.joining("\n", "### 项目规范\n", ""));
    }

    @Override
    public void recordFinding(String projectId, String anchorClass, String anchorMethod,
                              String sessionId, String requirementId, String requirementTitle,
                              Boolean passed, String severity, String category,
                              String findingText, String suggestion, String codeReferences) {
        ReviewFinding finding = new ReviewFinding();
        finding.setProjectId(projectId);
        finding.setAnchorClass(anchorClass);
        finding.setAnchorMethod(anchorMethod);
        finding.setSessionId(sessionId);
        finding.setRequirementId(requirementId);
        finding.setRequirementTitle(requirementTitle);
        finding.setPassed(passed);
        finding.setSeverity(severity);
        finding.setCategory(category);
        finding.setFindingText(findingText);
        finding.setSuggestion(suggestion);
        finding.setCodeReferences(codeReferences);
        findingRepo.save(finding);
    }

    @Override
    public void recordSession(String sessionId, String projectId, String anchorClass,
                              String anchorMethod, String strategy, String requirements,
                              String chainClasses, String fullReport, String model,
                              int promptTokens, int completionTokens, int totalTokens,
                              int durationMs, String summary, String status,
                              String commitId, String tag) {
        recordSession(sessionId, projectId, anchorClass, anchorMethod, strategy, requirements,
                chainClasses, fullReport, model, promptTokens, completionTokens, totalTokens,
                durationMs, summary, status, commitId, tag, null, null, null);
    }

    @Override
    public void recordSession(String sessionId, String projectId, String anchorClass,
                              String anchorMethod, String strategy, String requirements,
                              String chainClasses, String fullReport, String model,
                              int promptTokens, int completionTokens, int totalTokens,
                              int durationMs, String summary, String status,
                              String commitId, String tag,
                              String changeNumber, Integer patchsetNumber, Long changeReviewTaskId) {
        ReviewSession session = new ReviewSession();
        session.setId(sessionId);
        session.setProjectId(projectId);
        session.setAnchorClass(anchorClass);
        session.setAnchorMethod(anchorMethod);
        session.setStrategy(strategy);
        session.setCommitId(commitId);
        session.setTag(tag);
        session.setChangeNumber(changeNumber);
        session.setPatchsetNumber(patchsetNumber);
        session.setChangeReviewTaskId(changeReviewTaskId);
        session.setRequirements(requirements);
        session.setChainClasses(chainClasses);
        session.setFullReport(fullReport);
        session.setModel(model);
        session.setPromptTokens(promptTokens);
        session.setCompletionTokens(completionTokens);
        session.setTotalTokens(totalTokens);
        session.setDurationMs(durationMs);
        session.setSummary(summary);
        session.setStatus(status);
        sessionRepo.save(session);
    }

    @Override
    public List<ReviewFinding> getFindings(String projectId, String anchorClass) {
        return findingRepo.findByProjectIdAndAnchorClass(projectId, anchorClass);
    }

    @Override
    public List<ReviewSession> getSessions(String projectId) {
        return sessionRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Override
    public List<ReviewSession> getSessionsByAnchor(String projectId, String anchorClass, String anchorMethod) {
        return sessionRepo.findByProjectIdAndAnchorClassAndAnchorMethodOrderByCreatedAtDesc(
                projectId, anchorClass, anchorMethod);
    }

    @Override
    public Page<ReviewSession> getSessionsFiltered(String projectId, String status, String anchorClass,
                                                    LocalDateTime startTime, LocalDateTime endTime,
                                                    String commitId, int page, int size) {
        return sessionRepo.findSessionsFiltered(projectId, status, anchorClass, startTime, endTime, commitId,
                PageRequest.of(page, size));
    }

    @Override
    public Optional<ReviewSession> getSessionById(String sessionId) {
        return sessionRepo.findById(sessionId);
    }

    @Override
    public List<ReviewFinding> getFindingsBySession(String sessionId) {
        return findingRepo.findBySessionId(sessionId);
    }

    @Override
    public List<ReviewSession> getSessionsByChangeTask(Long changeReviewTaskId) {
        return sessionRepo.findByChangeReviewTaskIdOrderByCreatedAtAsc(changeReviewTaskId);
    }

    @Override
    public void recordKnowledge(ProjectKnowledge knowledge) {
        knowledgeRepo.save(knowledge);
    }

    @Override
    public List<ProjectKnowledge> getActiveKnowledge(String projectId) {
        return knowledgeRepo.findByProjectIdAndStatusIn(
                projectId,
                List.of(KnowledgeStatus.ESTABLISHED, KnowledgeStatus.CONFIRMED, KnowledgeStatus.TENTATIVE));
    }

    @Override
    public List<ProjectKnowledge> getKnowledgeByType(String projectId, KnowledgeType type) {
        return knowledgeRepo.findByProjectIdAndKnowledgeType(projectId, type);
    }
}
