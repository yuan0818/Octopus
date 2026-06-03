package io.yuan.octopus.dto.request;

import io.yuan.octopus.exception.ValidationException;

import java.util.List;

public record SourcePathRequest(
        Long projectId,
        String branch,
        String commitId,
        List<String> signatures
) {
    public SourcePathRequest {
        if (signatures == null || signatures.isEmpty()
                || signatures.stream().anyMatch(signature -> signature == null || signature.isBlank())) {
            throw new ValidationException("signatures must contain at least one non-blank method signature");
        }
    }
}
