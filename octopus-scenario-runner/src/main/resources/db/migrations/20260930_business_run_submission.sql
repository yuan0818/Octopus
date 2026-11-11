-- Existing installations: apply before enabling the new run submission UI.
-- The normal initialization schema includes this table for fresh installations.
CREATE TABLE IF NOT EXISTS business_run_submission (
    id VARCHAR(64) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    input_hash VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL
);
