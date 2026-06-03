package io.yuan.octopus.entity;

public sealed interface RepoSource permits ConfiguredRepoSource, DirectRepoSource{
}
