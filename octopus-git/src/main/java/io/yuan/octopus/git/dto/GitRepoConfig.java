package io.yuan.octopus.git.dto;

/**
 * Lightweight repository configuration with plain-text credentials.
 * The caller is responsible for decrypting credentials before sending.
 */
public record GitRepoConfig(
    String repoUrl,
    String branch,
    String authType,     // "NONE", "HTTP", "SSH"
    String httpUsername,
    String httpPassword,  // plain text, already decrypted by caller
    String sshPrivateKeyPath,
    String sshPassphrase, // plain text, already decrypted by caller
    String sshKnownHostsPath
) {}
