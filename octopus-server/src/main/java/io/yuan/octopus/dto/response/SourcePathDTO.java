package io.yuan.octopus.dto.response;

import java.util.List;

public record SourcePathDTO(
        List<CodePathDTO.CodePathStepDTO> steps
) {}
