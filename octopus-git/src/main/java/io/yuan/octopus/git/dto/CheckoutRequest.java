package io.yuan.octopus.git.dto;

/**
 * Request body for the checkout endpoint.
 */
public record CheckoutRequest(
    String repoUrl,
    String branch,
    String authType,
    String httpUsername,
    String httpPassword,
    String sshPrivateKeyPath,
    String sshPassphrase,
    String sshKnownHostsPath,
    String tag        // the tag to check out
) {}
