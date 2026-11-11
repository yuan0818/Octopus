-- 用例生成三角色（2026-10）：生成任务、生成探测运行与探测环境授权。已有安装执行一次；全新安装的 ../business-testing.sql 已包含。
-- 需求：docs/requirements/20261023_case-generation-agents_requirements.md；设计：docs/design/20261026_case-generation-agents_design.md
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
