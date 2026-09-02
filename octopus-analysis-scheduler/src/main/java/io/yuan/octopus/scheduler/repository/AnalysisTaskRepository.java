package io.yuan.octopus.scheduler.repository;

import io.yuan.octopus.scheduler.entity.AnalysisTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    List<AnalysisTask> findByStatus(String status);
    List<AnalysisTask> findByStatusOrderByCreatedAtDesc(String status);
    List<AnalysisTask> findByRepoConfigIdOrderByCreatedAtDesc(Long repoConfigId);

    Page<AnalysisTask> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<AnalysisTask> findByRepoConfigIdOrderByCreatedAtDesc(Long repoConfigId, Pageable pageable);
}
