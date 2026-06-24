package io.yuan.octopus.dto.response;

import java.util.List;

public record CodePathDTO(
        String direction,
        List<CodePathStepDTO> steps
) {
    public record CodePathStepDTO(
            MethodNodeDTO method,
            String callType,
            int lineNumber,
            String sourceFilePath,
            String sourceCode
    ) {}
}
