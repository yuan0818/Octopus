package io.yuan.octopus.reconstruct.dto;

import jakarta.validation.constraints.NotBlank;

public record ReconstructRequest(
    @NotBlank String className,
    @NotBlank String methodName,
    Integer maxDepth
) {
    public int getEffectiveDepth() {
        return maxDepth != null && maxDepth > 0 ? maxDepth : 10;
    }
}
