package io.yuan.octopus.dto.response;

import java.util.List;

/**
 * 同一入口在两个分析版本之间的链路差异（L1 链路成员 + 源码 diff）。
 *
 * <p>"版本" = (branch, commitId) 对——同一 project 内多分支/多提交并存，
 * 两个版本各自枚举入口的下游链路成员集合后做差集；共同成员比较
 * MethodNode 上版本化的 sourceCode 得出变更方法。
 *
 * @param anchor         入口方法（取自任一存在的版本）
 * @param fromVersion    对比基准版本
 * @param toVersion      对比目标版本
 * @param added          仅存在于目标版本的链路成员
 * @param removed        仅存在于基准版本的链路成员
 * @param changed        两版本都存在但源码有变化的方法（含双版本源码）
 * @param unchangedCount 源码未变化的成员数量
 * @param truncated      成员数超上限被截断
 * @param gitBacked      变更文本是否取自 git blob（false = 回退分析时快照，
 *                       仅无 git 的本地项目/服务不可达时出现）
 */
public record EntryChainDiffDTO(
        MethodRef anchor,
        VersionRef fromVersion,
        VersionRef toVersion,
        List<MethodRef> added,
        List<MethodRef> removed,
        List<ChangedMethod> changed,
        int unchangedCount,
        boolean truncated,
        boolean gitBacked
) {

    public record MethodRef(String signature, String qualifiedClassName, String methodName) {}

    public record ChangedMethod(MethodRef method, String fromSource, String toSource) {}

    public record VersionRef(String branch, String commitId) {}
}
