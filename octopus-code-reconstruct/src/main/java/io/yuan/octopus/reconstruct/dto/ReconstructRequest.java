package io.yuan.octopus.reconstruct.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReconstructRequest(

    @NotNull Long projectId,

    @NotBlank String className,

    @NotBlank String methodName,

    // Deprecated: use strategy instead — project now traverses full chain without depth limit
    Integer maxDepth,

    /** BFS or DFS traversal strategy. Defaults to BFS when null. */
    String strategy
) {
    public String getStrategy() {
        return strategy != null ? strategy : "BFS";
    }
}
