package io.yuan.octopus.dto.request;

import io.yuan.octopus.common.AuthType;

public record CreateRepoConfigRequest(
        String repoUrl,
        String branch,
        AuthType authType,
        String httpUsername,
        String httpCredential,
        String sshPrivateKeyPath,
        String sshPassphrase,
        String sshKnownHostsPath
) {
}
