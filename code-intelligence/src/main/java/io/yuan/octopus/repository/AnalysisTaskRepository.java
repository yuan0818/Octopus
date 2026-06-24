package io.yuan.octopus.repository;

import io.yuan.octopus.entity.AnalysisTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {

    List<AnalysisTask> findAllByOrderByCreatedAtDescIdDesc();
}
