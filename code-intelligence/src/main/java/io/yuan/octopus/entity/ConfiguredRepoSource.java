package io.yuan.octopus.entity;

public record ConfiguredRepoSource(String repoName, String branch) implements RepoSource{



}
