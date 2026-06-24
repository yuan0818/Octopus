package io.yuan.octopus.entity;

public record DirectRepoSource(String repoUrl, String branch) implements RepoSource {
}
