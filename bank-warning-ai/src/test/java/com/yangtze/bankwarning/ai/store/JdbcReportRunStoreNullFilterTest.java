package com.yangtze.bankwarning.ai.store;

import com.yangtze.bankwarning.security.security.CustomUserDetails;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 回归：管理员（dataFilter == null）触发出报告时，ReportRunStore 的全部语句都必须可用。
 *
 * <p>复现的线上报错：
 * {@code PSQLException: 错误: 无法确定参数 $3 的数据类型}。
 * 起因是 {@code (user_id = ? OR ? IS NULL)} 里的裸 NULL 参数没有类型，
 * pgjdbc 以 OID 0 下发，PostgreSQL 无法推断。历史上管理员被
 * {@code SecurityUtils} 错判成普通用户（dataFilter 恒非 null）因而没暴露；
 * 修正管理员判定后，点「AI 出报告」立刻在 {@code findLatestByTaskId} 报错。
 *
 * <p>本测试直接打真实 PostgreSQL，覆盖「null 过滤」下的写读全链路。
 */
@DisplayName("ReportRunStore 管理员(null) 全链路")
class JdbcReportRunStoreNullFilterTest {

    private static boolean dbAvailable;

    @BeforeAll
    static void probeDb() {
        // 依赖真实 PostgreSQL 才能复现 pgjdbc 的参数类型推断问题；
        // 无库环境下跳过（而不是误报失败），本地/联调机上是有效回归。
        try {
            jdbc().queryForObject("SELECT 1", Integer.class);
            dbAvailable = true;
        } catch (Exception e) {
            dbAvailable = false;
        }
    }

    private static JdbcTemplate jdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(System.getenv().getOrDefault("BANK_DB_URL",
                "jdbc:postgresql://localhost:5432/bank_risk_db"));
        ds.setUsername(System.getenv().getOrDefault("BANK_DB_USERNAME", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("BANK_DB_PASSWORD", "123456"));
        return new JdbcTemplate(ds);
    }

    @Test
    @DisplayName("findLatestByTaskId(_, null) 不应抛异常")
    void findLatestWithNullFilter() {
        assumeTrue(dbAvailable, "本机无 PostgreSQL，跳过");
        JdbcReportRunStore store = new JdbcReportRunStore(jdbc());
        assertDoesNotThrow(() -> store.findLatestByTaskId("__no_such_task__", null),
                "admin(null) 过滤路径必须可用");
        assertTrue(store.findLatestByTaskId("__no_such_task__", null).isEmpty());
    }

    @Test
    @DisplayName("create/claim/update/finish 全链路在 null 过滤下可用")
    void fullLifecycleWithNullFilter() {
        assumeTrue(dbAvailable, "本机无 PostgreSQL，跳过");
        JdbcTemplate jdbc = jdbc();
        JdbcReportRunStore store = new JdbcReportRunStore(jdbc);
        jdbc.execute("""
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

        String runId = "test-" + UUID.randomUUID();
        String taskId = "__test_null_filter__";
        try {
            // create：内部会 findByRunId(runId, userId=null)
            assertDoesNotThrow(() -> store.create(runId, taskId, null, "[]", "{}"));
            // claimRunning
            assertTrue(store.claimRunning(runId, null), "claimRunning(null) 应成功");
            // updateProgress
            assertDoesNotThrow(() -> store.updateProgress(
                    runId, null, 2, "[\"DONE\",\"RUNNING\"]", "{\"k\":\"v\"}"));
            // markFinished
            assertDoesNotThrow(() -> store.markFinished(runId, null, "report_x.md"));

            ReportRunStore.ReportRun saved = store.findLatestByTaskId(taskId, null).orElseThrow();
            assertEquals(ReportRunStore.STATUS_FINISHED, saved.status());
            assertEquals(2, saved.currentStep());
            assertEquals("report_x.md", saved.reportFileName());
        } finally {
            jdbc.update("DELETE FROM ai_report_runs WHERE run_id = ?", runId);
        }
    }

    @Test
    @DisplayName("闭环：SUPER_ADMIN 身份 → dataFilter=null → 不再抛 SQL 异常")
    void adminChainEndToEnd() {
        assumeTrue(dbAvailable, "本机无 PostgreSQL，跳过");

        // 还原线上登录态：admin 账号 role=SUPER_ADMIN → authority=ROLE_SUPER_ADMIN
        CustomUserDetails admin = new CustomUserDetails(4L, "admin", "",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        admin, null, admin.getAuthorities()));
        try {
            Long dataFilter = SecurityUtils.getCurrentUserIdForDataFilter();
            assertNull(dataFilter, "管理员应返回 null（看全部）");

            JdbcReportRunStore store = new JdbcReportRunStore(jdbc());
            // 修复前：这一步就是点「AI 出报告」时报错的位置
            assertDoesNotThrow(
                    () -> store.findLatestByTaskId("__no_such_task__", dataFilter));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
