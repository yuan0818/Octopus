package io.yuan.octopus.dto.response;

import java.util.List;

public record ImpactPathsDTO(
        MethodNodeDTO anchor,
        List<CodePathDTO> upstreamPaths,
        List<CodePathDTO> downstreamPaths,
        List<CodePathDTO> affectedPaths,
        boolean truncated
) {}
