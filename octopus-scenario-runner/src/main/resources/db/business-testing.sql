-- Additive migration. Existing scenario tables and data are not modified.
CREATE TABLE IF NOT EXISTS business_repository_analysis (
    id VARCHAR(64) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(24) NOT NULL,
    owner VARCHAR(36),
    lease_until BIGINT NOT NULL DEFAULT 0,
    snapshot_encrypted LONGTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_bt_repo_analysis_state (state, updated_at)
);
CREATE TABLE IF NOT EXISTS business_flow_chain (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    flow_id VARCHAR(120) NOT NULL,
    active_flow VARCHAR(120) UNIQUE,
    state VARCHAR(24) NOT NULL,
    snapshot_encrypted LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_bt_chain_flow (flow_id, created_at),
    INDEX idx_bt_chain_state (state, updated_at)
);
CREATE TABLE IF NOT EXISTS business_step_memory (
    id VARCHAR(36) PRIMARY KEY,
    flow_id VARCHAR(120) NOT NULL,
    node_id VARCHAR(120) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    confirmation_hash VARCHAR(64) NOT NULL,
    model VARCHAR(200) NOT NULL,
    memory_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_bt_step_memory_flow (flow_id, environment, state, created_at)
);
CREATE TABLE IF NOT EXISTS business_project_context (
    id VARCHAR(64) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    repository_key VARCHAR(1000) NOT NULL,
    module_path VARCHAR(1000) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    definition_json LONGTEXT NOT NULL,
    credentials_encrypted LONGTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS business_test_scenario (
    id VARCHAR(36) PRIMARY KEY,
    flow_id VARCHAR(120) NOT NULL,
    flow_revision INT NOT NULL,
    test_point_id VARCHAR(120) NOT NULL,
    ir_hash VARCHAR(64) NOT NULL,
    ir_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_bt_scenario_flow (flow_id, flow_revision)
);
CREATE TABLE IF NOT EXISTS business_test_run (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    scenario_id VARCHAR(36) NOT NULL,
    state VARCHAR(24) NOT NULL,
    verdict VARCHAR(24) NOT NULL,
    report_json LONGTEXT,
    error VARCHAR(255),
    created_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    worker_id VARCHAR(96),
    heartbeat_at DATETIME(6),
    INDEX idx_bt_run_scenario (scenario_id),
    INDEX idx_bt_run_state (state)
);

-- Durable idempotency receipts; keep for at least 24 hours and through the run's activity.
CREATE TABLE IF NOT EXISTS business_run_submission (
    id VARCHAR(64) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    input_hash VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS business_repository_analysis_history (
    id VARCHAR(64) PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    is_current BOOLEAN NOT NULL,
    summary_encrypted LONGTEXT NOT NULL,
    snapshot_encrypted LONGTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_bt_repo_history_scope (scope_id, updated_at)
);

CREATE TABLE IF NOT EXISTS business_repository_background_adoption (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64),
    adopted_at DATETIME(6)
);

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

-- Phase 2.4：数据驱动执行（同一场景按数据表逐行运行）
CREATE TABLE IF NOT EXISTS business_data_run (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    scenario_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    verdict VARCHAR(24) NOT NULL,
    profile_json LONGTEXT NOT NULL,
    rows_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    INDEX idx_bt_data_run_scenario (scenario_id),
    INDEX idx_bt_data_run_status (status)
);

-- DSL I1（2026-10）：JMeter 扩展、轮询授权与 DSL 来源
CREATE TABLE IF NOT EXISTS business_tool_extension (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    file_name VARCHAR(255) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    deprecated VARCHAR(500),
    tools_json LONGTEXT NOT NULL,
    issues_json LONGTEXT NOT NULL,
    class_names_json LONGTEXT NOT NULL,
    uploaded_by VARCHAR(128),
    uploaded_at DATETIME(6) NOT NULL,
    published_by VARCHAR(128),
    published_at DATETIME(6),
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_bt_extension_sha (sha256),
    INDEX idx_bt_extension_status (status)
);

CREATE TABLE IF NOT EXISTS business_poll_authorization (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    project_id BIGINT NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    path VARCHAR(512) NOT NULL,
    signature VARCHAR(1024) NOT NULL,
    revision INT NOT NULL,
    interval_millis BIGINT NOT NULL,
    max_attempts INT NOT NULL,
    timeout_millis BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(128),
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_bt_poll_api (project_id, http_method, path(191))
);

CREATE TABLE IF NOT EXISTS business_dsl_source (
    id VARCHAR(200) PRIMARY KEY,
    flow_id VARCHAR(160) NOT NULL,
    revision INT NOT NULL,
    scenario_id VARCHAR(96) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    flow_hash VARCHAR(64) NOT NULL,
    source LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_bt_dsl_flow (flow_id)
);

-- 用例生成三角色（2026-10），同 migrations/20261026_case_generation_agents.sql
CREATE TABLE IF NOT EXISTS business_case_generation_task (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    request_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    flow_id VARCHAR(160) NOT NULL,
    flow_revision INT NOT NULL,
    active_flow VARCHAR(160),
    state VARCHAR(24) NOT NULL,
    phase VARCHAR(24),
    round_no INT NOT NULL DEFAULT 0,
    probe_mode VARCHAR(8) NOT NULL,
    probe_environment VARCHAR(64),
    snapshot_encrypted LONGTEXT NOT NULL,
    owner VARCHAR(96),
    lease_until BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_bt_casegen_request (request_id),
    UNIQUE KEY uk_bt_casegen_active (active_flow),
    INDEX idx_bt_casegen_flow (flow_id, created_at),
    INDEX idx_bt_casegen_state (state)
);

CREATE TABLE IF NOT EXISTS business_generation_probe_run (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(160) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    round_no INT NOT NULL,
    intent_id VARCHAR(96) NOT NULL,
    candidate_hash VARCHAR(64) NOT NULL,
    ir_hash VARCHAR(64) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    probe_env_revision INT NOT NULL,
    namespace VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    requests_may_have_been_sent BOOLEAN NOT NULL DEFAULT FALSE,
    report_json LONGTEXT,
    evidence_json LONGTEXT,
    cleanup_status VARCHAR(24),
    error VARCHAR(500),
    worker_id VARCHAR(96),
    heartbeat_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    UNIQUE KEY uk_bt_probe_idem (idempotency_key),
    INDEX idx_bt_probe_task (task_id, round_no),
    INDEX idx_bt_probe_state (state)
);

CREATE TABLE IF NOT EXISTS business_probe_environment (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    workspace_id VARCHAR(64) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    data_policy VARCHAR(24) NOT NULL,
    reset_cycle_hours INT,
    non_production_declared BOOLEAN NOT NULL,
    reason VARCHAR(500) NOT NULL,
    revision INT NOT NULL,
    updated_by VARCHAR(128),
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_bt_probe_env (workspace_id, environment)
);

CREATE TABLE IF NOT EXISTS business_probe_environment_history (
    id VARCHAR(36) PRIMARY KEY,
    probe_environment_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    changed_by VARCHAR(128),
    changed_at DATETIME(6) NOT NULL,
    INDEX idx_bt_probe_env_hist (probe_environment_id, revision)
);
