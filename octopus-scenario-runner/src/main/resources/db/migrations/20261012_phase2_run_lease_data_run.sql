-- Phase 2.2（2026-10）：运行租约。已有安装执行一次；全新安装的 ../business-testing.sql 已包含这些列。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS：若列已存在会报 Duplicate column，可忽略该条。
ALTER TABLE business_test_run ADD COLUMN worker_id VARCHAR(96) NULL;
ALTER TABLE business_test_run ADD COLUMN heartbeat_at DATETIME(6) NULL;
CREATE INDEX idx_bt_run_state ON business_test_run (state);

-- Phase 2.4：数据驱动执行
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
