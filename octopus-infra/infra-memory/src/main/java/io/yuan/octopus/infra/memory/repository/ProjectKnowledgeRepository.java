package io.yuan.octopus.infra.memory.repository;

import io.yuan.octopus.infra.memory.model.KnowledgeStatus;
import io.yuan.octopus.infra.memory.model.KnowledgeType;
import io.yuan.octopus.infra.memory.model.ProjectKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ProjectKnowledgeRepository extends JpaRepository<ProjectKnowledge, Long> {

    List<ProjectKnowledge> findByProjectId(String projectId);

    List<ProjectKnowledge> findByProjectIdAndKnowledgeType(String projectId, KnowledgeType type);

    List<ProjectKnowledge> findByProjectIdAndStatus(String projectId, KnowledgeStatus status);

    List<ProjectKnowledge> findByProjectIdAndStatusIn(String projectId, List<KnowledgeStatus> statuses);

    List<ProjectKnowledge> findByProjectIdAndKnowledgeTypeAndStatusIn(
            String projectId, KnowledgeType type, List<KnowledgeStatus> statuses);

    List<ProjectKnowledge> findByLastVerifiedAtBefore(LocalDateTime before);
}
