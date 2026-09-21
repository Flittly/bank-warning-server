-- V9__create_ai_report_runs.sql
-- 创建 AI 报告工作流运行状态表（P0-1 Phase 1）
--
-- 注意：JdbcReportRunStore 启动时也会自举建表（CREATE TABLE IF NOT EXISTS），
-- 本脚本用于 Flyway/手动迁移，两者保持一致。

CREATE TABLE IF NOT EXISTS ai_report_runs (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    user_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_step INT NOT NULL DEFAULT -1,
    step_states_json TEXT,
    context_json TEXT,
    last_error TEXT,
    report_file_name VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_report_runs_run_id ON ai_report_runs(run_id);
CREATE INDEX IF NOT EXISTS idx_ai_report_runs_task_id ON ai_report_runs(task_id);

-- 同一 task 同一时刻只允许一个 PENDING/RUNNING 运行（并发防重复触发的数据库兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_report_runs_active_task
    ON ai_report_runs(task_id) WHERE status IN ('PENDING', 'RUNNING');
