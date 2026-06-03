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
