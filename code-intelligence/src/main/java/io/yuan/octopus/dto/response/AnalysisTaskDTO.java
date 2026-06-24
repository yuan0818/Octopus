package io.yuan.octopus.dto.response;

import io.yuan.octopus.entity.AnalysisTaskStep;

import java.time.LocalDateTime;
import java.util.List;

public record AnalysisTaskDTO(
        Long id,
        Long repoConfigId,
        String status,
        String currentStep,
        String commitId,
        String tag,
        String branch,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<AnalysisTaskStep> steps
) {}
