package io.yuan.octopus.dto.response;

import java.util.List;

public record EndpointDiffPathsDTO(
        String endpointSignature,
        String fromCommitId,
        String toCommitId,
        List<CodePathDTO> addedPaths,
        List<CodePathDTO> removedPaths,
        List<CodePathDTO> unchangedPaths,
        boolean truncated
) {}
