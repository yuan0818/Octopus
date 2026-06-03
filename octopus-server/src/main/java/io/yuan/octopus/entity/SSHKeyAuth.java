package io.yuan.octopus.entity;

public record SSHKeyAuth(String privateKeyPath, String passphrase, String knownHostsPath) implements GitAuth{
}
