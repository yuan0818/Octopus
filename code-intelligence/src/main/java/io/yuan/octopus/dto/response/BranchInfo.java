package io.yuan.octopus.dto.response;

/**
 * 一个 Git 分支的概要信息。
 *
 * @param name           分支名（不含 refs 前缀）
 * @param latestCommitId 该分支 HEAD 指向的完整 commit SHA，解析失败时为 null
 */
public record BranchInfo(
    String name,
    String latestCommitId
) {}
