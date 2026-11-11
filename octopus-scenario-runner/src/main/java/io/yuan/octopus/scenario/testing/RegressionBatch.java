package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 变更触发的批量回归（Phase 1A）。一行对应一次"双版本影响分析 → 受影响测试点 → 批量执行"。
 * <p>
 * 条目与汇总以 JSON 保存（条目数量有上限），状态推进由 {@link RegressionBatchService} 的调度线程完成；
 * 服务重启后仍为 OPEN 的批次会被继续推进。
 */
@Entity
@Table(name = "business_regression_batch")
public class RegressionBatch {
    @Id public String id;
    @Version public long version;

    /** MANUAL / GERRIT / CI。 */
    @Column(nullable = false, length = 16)
    public String triggerSource;

    /** 外部引用（如 gerrit:project~change~patchset），仅用于展示和回调。 */
    @Column(length = 255)
    public String externalRef;

    /** 幂等键（同一键重复提交返回已有批次）。 */
    @Column(length = 128, unique = true)
    public String idempotencyKey;

    /** OPEN（推进中）/ COMPLETED / CANCELLED / FAILED（影响分析或生成失败）。 */
    @Column(nullable = false, length = 16)
    public String status;

    /** PASS / FAIL / INCONCLUSIVE / NO_AFFECTED_TESTS；OPEN 时为 PENDING。 */
    @Column(nullable = false, length = 24)
    public String verdict;

    /** 原始请求（不含初始上下文的实际值，只保存键名）。 */
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String requestJson;

    /** 影响分析摘要：status、unmappedEndpoints、evidence 数量等。 */
    @Column(columnDefinition = "LONGTEXT")
    public String impactJson;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String itemsJson;

    @Column(length = 500)
    public String callbackUrl;

    /** NOT_REQUIRED / PENDING / DELIVERED / FAILED。 */
    @Column(nullable = false, length = 16)
    public String callbackStatus;

    @Column(length = 500)
    public String error;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    public Instant finishedAt;
}
