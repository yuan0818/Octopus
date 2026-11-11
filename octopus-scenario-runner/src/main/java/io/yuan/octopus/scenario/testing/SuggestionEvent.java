package io.yuan.octopus.scenario.testing;

import jakarta.persistence.*;

import java.time.Instant;

/** Phase 1E：一次模型调用或一次人工采纳/拒绝事件。不保存提示词、模型输出原文或任何业务数据。 */
@Entity
@Table(name = "business_suggestion_event")
public class SuggestionEvent {
    @Id public String id;

    /** STEP / CHAIN / FLOW_SUGGEST / ORDERED_PLAN / VERIFICATION / REPOSITORY_* / RUN_TRIAGE。 */
    @Column(nullable = false, length = 32)
    public String kind;

    /** GENERATED / FAILED / ACCEPTED / REJECTED / EDITED。 */
    @Column(nullable = false, length = 16)
    public String outcome;

    @Column(length = 128)
    public String model;

    /** 关联对象（memoryId、chainId、runId 等），用于把生成与采纳对应起来。 */
    @Column(length = 128)
    public String refId;

    public Long latencyMs;

    public Integer responseChars;

    /** 采纳时人工改动的条目数，或生成结果包含的条目数。 */
    public Integer changedItems;

    @Column(length = 48)
    public String failureCode;

    @Column(nullable = false)
    public Instant createdAt;
}
