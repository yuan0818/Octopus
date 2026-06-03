package io.yuan.octopus.infra.jgit;

/**
 * Git authentication configuration.
 */
public record AuthConfig(
    AuthType type,
    String httpUsername,
    String httpPassword,
    String sshPrivateKeyPath,
    String sshPassphrase,
    String sshKnownHostsPath
) {
    public enum AuthType { NONE, HTTP, SSH }
}
