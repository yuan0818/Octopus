package io.yuan.octopus.dto.response;

import io.yuan.octopus.common.AuthType;

import java.time.LocalDateTime;

public record RepoConfigSummary(
        Long id,
        String repoUrl,
        String branch,
        AuthType authType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
