package io.yuan.octopus.repository;

import io.yuan.octopus.entity.AnalysisTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {

    List<AnalysisTask> findAllByOrderByCreatedAtDescIdDesc();
    List<AnalysisTask> findByCommitIdAndBranchOrderByIdDesc(String commitId, String branch);

    /** 启动恢复：找出上次进程退出时仍处于某状态（RUNNING）的任务。 */
    List<AnalysisTask> findByStatus(String status);
}
