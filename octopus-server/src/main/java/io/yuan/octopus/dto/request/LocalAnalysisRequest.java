package io.yuan.octopus.dto.request;

public record LocalAnalysisRequest(
    String projectPath,
    String projectName
) {
    public LocalAnalysisRequest {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath must not be blank");
        }
    }
}
