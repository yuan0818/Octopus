package io.yuan.octopus.infra.common.mq;

/**
 * 分析任务的 MQ 消息契约（octopus-analysis-scheduler 发布，code-intelligence 消费）。
 * <p>
 * 放在共享模块保证两侧类名（Jackson {@code __TypeId__}）一致；字段名即契约，
 * 两侧演进需保持兼容（只加不删）。
 *
 * @param taskId      scheduler 侧任务 id（仅追踪用，消费方不回写）
 * @param repoConfigId 仓库配置 id（消费方执行 tag 管线的必填项）
 * @param projectId   可选关联项目 id
 * @param branch      分支（可选）
 * @param commitId    commit（可选）
 * @param tag         要分析的 Git tag（tag 管线驱动）
 * @param language    JAVA / GO
 * @param analysisType FULL / INCREMENTAL（预留）
 * @param timestamp   发布时间
 */
public record AnalysisTaskMessage(
        Long taskId, Long repoConfigId, Long projectId, String branch, String commitId,
        String tag, String language, String analysisType, String timestamp) {
}
