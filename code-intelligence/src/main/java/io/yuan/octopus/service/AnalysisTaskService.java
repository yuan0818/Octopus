package io.yuan.octopus.service;

import io.yuan.octopus.entity.AnalysisTask;
import io.yuan.octopus.entity.AnalysisTaskStep;
import io.yuan.octopus.dto.response.AnalysisTaskDTO;
import io.yuan.octopus.exception.ResourceNotFoundException;
import io.yuan.octopus.repository.AnalysisTaskRepository;
import io.yuan.octopus.repository.AnalysisTaskStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AnalysisTaskService {

    private static final List<String> STEP_NAMES = List.of(
            "SYNC", "SCAN", "PARSE_DEEP"
    );

    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisTaskStepRepository analysisTaskStepRepository;

    public AnalysisTaskService(AnalysisTaskRepository analysisTaskRepository,
                               AnalysisTaskStepRepository analysisTaskStepRepository) {
        this.analysisTaskRepository = analysisTaskRepository;
        this.analysisTaskStepRepository = analysisTaskStepRepository;
    }

    private static final List<String> LOCAL_STEP_NAMES = List.of("SCAN", "PARSE_DEEP");

    @Transactional
    public AnalysisTask createTask(Long repoConfigId, String tag, String branch) {
        AnalysisTask task = AnalysisTask.builder()
                .repoConfigId(repoConfigId)
                .tag(tag)
                .branch(branch)
                .status("RUNNING")
                .build();
        task = analysisTaskRepository.save(task);

        for (String stepName : STEP_NAMES) {
            AnalysisTaskStep step = AnalysisTaskStep.builder()
                    .taskId(task.getId())
                    .stepName(stepName)
                    .status("PENDING")
                    .build();
            analysisTaskStepRepository.save(step);
        }

        return task;
    }

    @Transactional
    public AnalysisTask createLocalTask(Long repoConfigId, String tag, String branch) {
        AnalysisTask task = AnalysisTask.builder()
                .repoConfigId(repoConfigId)
                .tag(tag)
                .branch(branch)
                .status("RUNNING")
                .build();
        task = analysisTaskRepository.save(task);

        for (String stepName : LOCAL_STEP_NAMES) {
            AnalysisTaskStep step = AnalysisTaskStep.builder()
                    .taskId(task.getId())
                    .stepName(stepName)
                    .status("PENDING")
                    .build();
            analysisTaskStepRepository.save(step);
        }

        return task;
    }

    @Transactional
    public void updateStepStatus(Long stepId, String status, String errorMessage) {
        AnalysisTaskStep step = analysisTaskStepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("AnalysisTaskStep not found with id: " + stepId));
        step.setStatus(status);
        step.setErrorMessage(errorMessage);
        if ("RUNNING".equals(status)) {
            step.setStartedAt(LocalDateTime.now());
            AnalysisTask task = analysisTaskRepository.findById(step.getTaskId())
                    .orElseThrow(() -> new ResourceNotFoundException("AnalysisTask not found with id: " + step.getTaskId()));
            task.setCurrentStep(step.getStepName());
            analysisTaskRepository.save(task);
        }
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "DEGRADED".equals(status)) {
            step.setCompletedAt(LocalDateTime.now());
        }
        analysisTaskStepRepository.save(step);
    }

    @Transactional
    public void updateTaskStatus(Long taskId, String status, String errorMessage) {
        AnalysisTask task = analysisTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("AnalysisTask not found with id: " + taskId));
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        analysisTaskRepository.save(task);
    }

    @Transactional
    public void updateCommitId(Long taskId, String commitId) {
        AnalysisTask task = analysisTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("AnalysisTask not found with id: " + taskId));
        task.setCommitId(commitId);
        analysisTaskRepository.save(task);
    }

    /**
     * 把上次进程退出时仍为 RUNNING 的任务标记为 FAILED，使其可通过 /retry 从中断的步骤续跑。
     * <p>
     * 分析管线跑在进程内的线程池里，进程退出后这些任务不可能再推进；不处理的话它们会
     * 永远停在 RUNNING，前端一直轮询。中断步骤（RUNNING，若没有则第一个 PENDING）
     * 标为 FAILED，{@code retryAnalysis} 会从这一步开始重跑。
     *
     * @return 被标记的任务数
     */
    @Transactional
    public int failInterruptedTasks(String reason) {
        List<AnalysisTask> running = analysisTaskRepository.findByStatus("RUNNING");
        for (AnalysisTask task : running) {
            List<AnalysisTaskStep> steps = analysisTaskStepRepository.findByTaskIdOrderByIdAsc(task.getId());
            AnalysisTaskStep interrupted = steps.stream()
                    .filter(s -> "RUNNING".equals(s.getStatus())).findFirst()
                    .orElseGet(() -> steps.stream()
                            .filter(s -> "PENDING".equals(s.getStatus())).findFirst().orElse(null));
            if (interrupted != null) {
                interrupted.setStatus("FAILED");
                interrupted.setErrorMessage(reason);
                interrupted.setCompletedAt(LocalDateTime.now());
                analysisTaskStepRepository.save(interrupted);
            }
            task.setStatus("FAILED");
            task.setErrorMessage(reason);
            analysisTaskRepository.save(task);
        }
        return running.size();
    }

    public AnalysisTask getTask(Long taskId) {
        return analysisTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("AnalysisTask not found with id: " + taskId));
    }

    public List<AnalysisTaskStep> getSteps(Long taskId) {
        return analysisTaskStepRepository.findByTaskIdOrderByIdAsc(taskId);
    }

    public AnalysisTaskDTO getTaskDetails(Long taskId) {
        return toDTO(getTask(taskId));
    }

    public List<AnalysisTaskDTO> listTasks() {
        return analysisTaskRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(this::toDTO)
                .toList();
    }

    public AnalysisTaskDTO toDTO(AnalysisTask task) {
        return new AnalysisTaskDTO(
                task.getId(),
                task.getRepoConfigId(),
                task.getStatus(),
                task.getCurrentStep(),
                task.getCommitId(),
                task.getTag(),
                task.getBranch(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                getSteps(task.getId())
        );
    }
}
