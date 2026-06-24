package io.yuan.octopus.repository;

import io.yuan.octopus.entity.AnalysisTaskStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisTaskStepRepository extends JpaRepository<AnalysisTaskStep, Long> {

    List<AnalysisTaskStep> findByTaskIdOrderByIdAsc(Long taskId);
}
