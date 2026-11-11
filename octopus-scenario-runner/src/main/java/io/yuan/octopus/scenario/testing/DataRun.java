package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;

import java.time.Instant;

/** Phase 2.4：同一场景按数据表逐行执行（每行一组初始变量），汇总为一个结果。 */
@Entity
@Table(name = "business_data_run")
public class DataRun {
    @Id public String id;
    @Version public long version;

    @Column(nullable = false, length = 36)
    public String scenarioId;

    /** OPEN / COMPLETED / CANCELLED。 */
    @Column(nullable = false, length = 16)
    public String status;

    /** PENDING / PASS / FAIL / INCONCLUSIVE。 */
    @Column(nullable = false, length = 24)
    public String verdict;

    /** 执行配置（服务地址、环境、secret 引用名、超时；不含敏感值）。 */
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String profileJson;

    /** 数据行：名称、普通测试值、运行 id、结果。 */
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String rowsJson;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    public Instant finishedAt;
}
