package io.yuan.octopus.dto.response;

public record ProjectSummaryDTO(
    Long id,
    String projectName,
    String projectPath,
    String branch,
    String commitId,
    Boolean isBackendProject,
    Boolean isApiService,
    String buildTool,
    String javaVersion,
    String repositoryKey,
    String repositoryName,
    String repositoryRootPath,
    String modulePath
) {}
