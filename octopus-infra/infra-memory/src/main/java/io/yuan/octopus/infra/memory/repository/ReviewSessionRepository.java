package io.yuan.octopus.infra.memory.repository;

import io.yuan.octopus.infra.memory.model.ReviewSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, String> {

    List<ReviewSession> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<ReviewSession> findByProjectIdAndAnchorClassAndAnchorMethodOrderByCreatedAtDesc(
            String projectId, String anchorClass, String anchorMethod);

    Page<ReviewSession> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    Page<ReviewSession> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status, Pageable pageable);

    @Query("SELECT rs FROM ReviewSession rs WHERE rs.projectId = :projectId " +
           "AND (:status IS NULL OR rs.status = :status) " +
           "AND (:anchorClass IS NULL OR rs.anchorClass LIKE %:anchorClass%) " +
           "AND (:startTime IS NULL OR rs.createdAt >= :startTime) " +
           "AND (:endTime IS NULL OR rs.createdAt <= :endTime) " +
           "ORDER BY rs.createdAt DESC")
    Page<ReviewSession> findSessionsFiltered(
            @Param("projectId") String projectId,
            @Param("status") String status,
            @Param("anchorClass") String anchorClass,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    List<ReviewSession> findByProjectIdAndAnchorClassOrderByCreatedAtDesc(String projectId, String anchorClass);
}
