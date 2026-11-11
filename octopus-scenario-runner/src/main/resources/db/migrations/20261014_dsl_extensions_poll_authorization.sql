-- DSL I1（2026-10）：JMeter 扩展、轮询授权与 DSL 来源。已有安装执行一次；全新安装的 ../business-testing.sql 已包含。
-- 注意：Phase 2.1 已确认的轮询流程在其 API 获得显式轮询授权前，运行会被拒绝（FR-POLL-06）。
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
