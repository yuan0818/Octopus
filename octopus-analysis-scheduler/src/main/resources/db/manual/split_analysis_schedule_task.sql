-- ============================================================================
-- 拆分 analysis_task：scheduler 追踪记录迁到独立表 analysis_schedule_task
-- ----------------------------------------------------------------------------
-- 背景：octopus-analysis-scheduler 与 code-intelligence 曾同时映射 analysis_task，
-- 两边 ddl-auto: update 互相 ALTER。scheduler 侧的 language / analysis_type 为
-- NOT NULL，code-intelligence 插入时不填，MySQL strict mode 下插入失败。
-- 现在 analysis_task 由 code-intelligence 独占，scheduler 使用 analysis_schedule_task。
--
-- 何时需要执行：仅当已有环境里 scheduler 曾写入过 analysis_task。全新环境不需要
-- （scheduler 启动时 Hibernate 会自动建 analysis_schedule_task）。
--
-- 执行顺序：
--   1. 停掉 octopus-analysis-scheduler 与 code-intelligence
--   2. 备份：mysqldump octopus analysis_task > analysis_task.bak.sql
--   3. 执行本脚本第 1、2 段，核对第 3 段的查询结果
--   4. 确认无误后再执行第 4 段（可选清理）
--   5. 启动新版本服务
-- ============================================================================

-- 1. 新表（与 scheduler 实体 io.yuan.octopus.scheduler.entity.AnalysisTask 一致）
CREATE TABLE IF NOT EXISTS analysis_schedule_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_config_id  BIGINT       NULL,
    project_id      BIGINT       NULL,
    branch          VARCHAR(200) NULL,
    commit_id       VARCHAR(100) NULL,
    tag             VARCHAR(200) NULL,
    language        VARCHAR(20)  NOT NULL DEFAULT 'JAVA',
    analysis_type   VARCHAR(20)  NOT NULL DEFAULT 'FULL',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NULL DEFAULT 0,
    max_retries     INT          NULL DEFAULT 3,
    error_message   TEXT         NULL,
    queue_name      VARCHAR(100) NULL,
    started_at      DATETIME(6)  NULL,
    completed_at    DATETIME(6)  NULL,
    timeout_seconds INT          NULL DEFAULT 1800,
    created_at      DATETIME(6)  NULL,
    updated_at      DATETIME(6)  NULL,
    KEY idx_sched_task_status (status),
    KEY idx_sched_task_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 2. 迁移 scheduler 写入的行。识别依据：scheduler 路由后一定写 queue_name，
--    code-intelligence 从不写这一列。保留原 id，便于与 MQ 消息里的 taskId 对应。
INSERT INTO analysis_schedule_task
    (id, repo_config_id, project_id, branch, commit_id, tag, language, analysis_type,
     status, retry_count, max_retries, error_message, queue_name, started_at,
     completed_at, timeout_seconds, created_at, updated_at)
SELECT id, repo_config_id, project_id, branch, commit_id, tag,
       COALESCE(NULLIF(language, ''), 'JAVA'), COALESCE(NULLIF(analysis_type, ''), 'FULL'),
       COALESCE(status, 'PENDING'), retry_count, max_retries, error_message, queue_name,
       started_at, completed_at, timeout_seconds, created_at, updated_at
FROM analysis_task
WHERE queue_name IS NOT NULL
  AND id NOT IN (SELECT id FROM analysis_schedule_task);

-- 新表自增从迁移后的最大 id 之后开始
SET @next_id = (SELECT COALESCE(MAX(id), 0) + 1 FROM analysis_schedule_task);
SET @sql = CONCAT('ALTER TABLE analysis_schedule_task AUTO_INCREMENT = ', @next_id);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. 核对：两边行数应一致
SELECT COUNT(*) AS migrated FROM analysis_schedule_task;
SELECT COUNT(*) AS to_remove FROM analysis_task WHERE queue_name IS NOT NULL;

-- 4.（可选，核对后执行）从 analysis_task 删除已迁移的行，并去掉只属于 scheduler 的列。
--    列被删除后，code-intelligence 的插入不再受 NOT NULL 约束影响。
-- DELETE FROM analysis_task WHERE queue_name IS NOT NULL;
-- ALTER TABLE analysis_task
--     DROP COLUMN project_id, DROP COLUMN language, DROP COLUMN analysis_type,
--     DROP COLUMN retry_count, DROP COLUMN max_retries, DROP COLUMN queue_name,
--     DROP COLUMN started_at, DROP COLUMN completed_at, DROP COLUMN timeout_seconds;
--
--    若暂不删列，至少去掉 NOT NULL，避免 code-intelligence 插入失败：
-- ALTER TABLE analysis_task
--     MODIFY language VARCHAR(20) NULL DEFAULT 'JAVA',
--     MODIFY analysis_type VARCHAR(20) NULL DEFAULT 'FULL';
