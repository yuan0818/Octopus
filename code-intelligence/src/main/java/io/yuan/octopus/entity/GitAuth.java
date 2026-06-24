package io.yuan.octopus.entity;

public sealed interface GitAuth permits HttpAuth, SSHKeyAuth {

}
