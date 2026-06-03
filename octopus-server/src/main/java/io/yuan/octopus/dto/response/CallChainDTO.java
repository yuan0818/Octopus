package io.yuan.octopus.dto.response;

import java.util.List;

public record CallChainDTO(
    MethodNodeDTO root,
    List<CallChainNodeDTO> chain
) {
    public record CallChainNodeDTO(
        MethodNodeDTO method,
        String callType,
        int lineNumber,
        List<CallChainNodeDTO> children
    ) {}
}
