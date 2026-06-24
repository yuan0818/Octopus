package io.yuan.octopus.dto.response;

public record EntryPointDTO(
    String entryType,
    String displayName,
    String triggerLabel,
    String httpMethod,
    String path,
    String methodName,
    String qualifiedClassName,
    String ownerSimpleName,
    String controllerSimpleName,
    String signature,
    Long projectId,
    String projectName,
    String branch,
    String commitId
) {}
