package io.yuan.octopus.dto.response;

/**
 * 一条 Git commit 的概要信息，供前端 commit 下拉选择器展示。
 *
 * @param commitId      完整 commit SHA
 * @param shortMessage  commit 标题（首行，已截断）
 * @param author        作者名
 * @param committedAt   提交时间（Unix epoch 秒）
 */
public record CommitInfo(
    String commitId,
    String shortMessage,
    String author,
    long committedAt
) {}
