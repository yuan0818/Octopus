package io.yuan.octopus.entity;

public record HttpAuth(String userName, String proof) implements GitAuth{

}
