package com.yangtze.bankwarning.ai.store;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 基于 JDBC 的报告工作流运行状态存储。
 *
 * 启动时自举建表（与 migration/V9 脚本一致）；同一 task 只能有一个
 * 活动（PENDING/RUNNING）运行，用 PostgreSQL 部分唯一索引保证。
 */
@Component
public class JdbcReportRunStore implements ReportRunStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcReportRunStore.class);

    private static final String SELECT_COLUMNS =
            "id, run_id, task_id, user_id, status, current_step, step_states_json, context_json, "
                    + "last_error, report_file_name, created_at, started_at, updated_at, completed_at ";

    private final JdbcTemplate jdbcTemplate;

    public JdbcReportRunStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("""
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
                )""");
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_report_runs_run_id ON ai_report_runs(run_id)");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_ai_report_runs_task_id ON ai_report_runs(task_id)");
        // 同一 task 同一时刻只允许一个 PENDING/RUNNING 运行
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_report_runs_active_task
                    ON ai_report_runs(task_id) WHERE status IN ('PENDING', 'RUNNING')
                """);
        log.info("[report-runs] ai_report_runs 表已就绪");
    }

    @Override
    public ReportRun create(String runId, String taskId, Long userId,
                            String stepStatesJson, String contextJson) {
        jdbcTemplate.update("""
                INSERT INTO ai_report_runs(run_id, task_id, user_id, status, current_step, step_states_json, context_json)
                VALUES (?, ?, ?, ?, -1, ?, ?)
                """, runId, taskId, userId, STATUS_PENDING, stepStatesJson, contextJson);
        return findByRunId(runId, userId).orElseThrow();
    }

    @Override
    public Optional<ReportRun> findLatestByTaskId(String taskId, Long userId) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS
                        + "FROM ai_report_runs WHERE task_id = ? AND (user_id = ? OR ? IS NULL) "
                        + "ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, taskId, userId, userId).stream().findFirst();
    }

    private Optional<ReportRun> findByRunId(String runId, Long userId) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS
                        + "FROM ai_report_runs WHERE run_id = ? AND (user_id = ? OR ? IS NULL) LIMIT 1",
                ROW_MAPPER, runId, userId, userId).stream().findFirst();
    }

    @Override
    public boolean claimRunning(String runId, Long userId) {
        return jdbcTemplate.update("""
                UPDATE ai_report_runs
                   SET status = ?, started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE run_id = ? AND status IN (?, ?) AND (user_id = ? OR ? IS NULL)
                """, STATUS_RUNNING, runId, STATUS_PENDING, STATUS_FAILED, userId, userId) > 0;
    }

    @Override
    public void updateProgress(String runId, Long userId, int currentStep,
                               String stepStatesJson, String contextJson) {
        jdbcTemplate.update("""
                UPDATE ai_report_runs
                   SET current_step = ?, step_states_json = ?, context_json = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE run_id = ? AND status = ? AND (user_id = ? OR ? IS NULL)
                """, currentStep, stepStatesJson, contextJson, runId, STATUS_RUNNING, userId, userId);
    }

    @Override
    public void markFailed(String runId, Long userId, String error) {
        jdbcTemplate.update("""
                UPDATE ai_report_runs
                   SET status = ?, last_error = ?, completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE run_id = ? AND status = ? AND (user_id = ? OR ? IS NULL)
                """, STATUS_FAILED, error, runId, STATUS_RUNNING, userId, userId);
    }

    @Override
    public void markFinished(String runId, Long userId, String reportFileName) {
        jdbcTemplate.update("""
                UPDATE ai_report_runs
                   SET status = ?, report_file_name = ?, completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE run_id = ? AND status = ? AND (user_id = ? OR ? IS NULL)
                """, STATUS_FINISHED, reportFileName, runId, STATUS_RUNNING, userId, userId);
    }

    @Override
    public int markStaleRunsFailed(String error) {
        return jdbcTemplate.update("""
                UPDATE ai_report_runs
                   SET status = ?, last_error = ?, completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE status = ?
                """, STATUS_FAILED, error, STATUS_RUNNING);
    }

    private static final RowMapper<ReportRun> ROW_MAPPER = (rs, rowNum) -> new ReportRun(
            rs.getLong("id"),
            rs.getString("run_id"),
            rs.getString("task_id"),
            rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
            rs.getString("status"),
            rs.getInt("current_step"),
            rs.getString("step_states_json"),
            rs.getString("context_json"),
            rs.getString("last_error"),
            rs.getString("report_file_name"),
            stringify(rs.getTimestamp("created_at")),
            stringify(rs.getTimestamp("started_at")),
            stringify(rs.getTimestamp("updated_at")),
            stringify(rs.getTimestamp("completed_at")));

    private static String stringify(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toString();
    }
}
