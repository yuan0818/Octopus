-- Existing installations: apply before enabling Phase 1 (batch regression, run triage, suggestion metrics).
-- The normal initialization schema (../business-testing.sql) already includes these tables.

-- ── Phase 1（2026-10）：变更触发批量回归 / LLM 失败分析 / LLM 建议度量 ──────────────

-- 1A 批量回归：一次双版本影响分析 → 受影响测试点 → 批量执行
CREATE TABLE IF NOT EXISTS business_regression_batch (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    trigger_source VARCHAR(16) NOT NULL,
    external_ref VARCHAR(255),
    idempotency_key VARCHAR(128) UNIQUE,
    status VARCHAR(16) NOT NULL,
    verdict VARCHAR(24) NOT NULL,
    request_json LONGTEXT NOT NULL,
    impact_json LONGTEXT,
    items_json LONGTEXT NOT NULL,
    callback_url VARCHAR(500),
    callback_status VARCHAR(16) NOT NULL,
    error VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    INDEX idx_bt_batch_status (status),
    INDEX idx_bt_batch_callback (callback_status)
);

-- 1D LLM 失败分析（只作建议，不改写运行判定）
CREATE TABLE IF NOT EXISTS business_run_triage (
    run_id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    category VARCHAR(32) NOT NULL,
    confidence VARCHAR(16) NOT NULL,
    triage_json LONGTEXT NOT NULL,
    model VARCHAR(128),
    created_at DATETIME(6) NOT NULL
);

-- 1E LLM 建议度量：每次模型调用与人工采纳/拒绝事件
CREATE TABLE IF NOT EXISTS business_suggestion_event (
    id VARCHAR(36) PRIMARY KEY,
    kind VARCHAR(32) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    model VARCHAR(128),
    ref_id VARCHAR(128),
    latency_ms BIGINT,
    response_chars INT,
    changed_items INT,
    failure_code VARCHAR(48),
    created_at DATETIME(6) NOT NULL,
    INDEX idx_bt_sugg_kind_time (kind, created_at)
);
