package io.yuan.octopus.infra.memory.repository;

import io.yuan.octopus.infra.memory.model.ReviewFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewFindingRepository extends JpaRepository<ReviewFinding, Long> {

    List<ReviewFinding> findByProjectIdAndAnchorClass(String projectId, String anchorClass);

    List<ReviewFinding> findByProjectIdAndRequirementId(String projectId, String requirementId);

    List<ReviewFinding> findBySessionId(String sessionId);

    List<ReviewFinding> findByProjectIdAndSeverity(String projectId, String severity);
}
