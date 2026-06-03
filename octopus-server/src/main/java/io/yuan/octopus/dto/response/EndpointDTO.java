package io.yuan.octopus.dto.response;

public record EndpointDTO(
    String httpMethod,
    String path,
    String methodName,
    String qualifiedClassName,
    String controllerSimpleName,
    String signature,
    Long projectId,
    String projectName,
    String branch,
    String commitId
) {}

