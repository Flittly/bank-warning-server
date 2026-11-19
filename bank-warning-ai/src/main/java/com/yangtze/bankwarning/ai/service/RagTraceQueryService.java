package com.yangtze.bankwarning.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 5：检索留痕的只读查询。
 *
 * <p><b>为什么必须有一个查询入口</b>：留痕表建好之后一直是 0 行，而「0 行」可以意味着
 * 三件完全不同的事——表没建、开关关了、根本没人提问。没有查询入口时，你只能用
 * {@code count(*)} 一条 SQL 手工判断，而「空返回率是多少、延迟 p95 多少」这类问题
 * 连手工都难以及时回答。**不查的留痕等于没有留痕。**
 *
 * <p><b>权限口径与知识库一致</b>：{@code userId == null} 表示管理员，看全部；
 * 普通用户只看自己的行。不做「管理员才能看」的硬拦截，是因为这个项目里
 * 数据范围本来就是<b>按 userId 收敛</b>的（见 {@code SecurityUtils.getCurrentUserIdForDataFilter()}），
 * 多一层角色判断反而会出现两套口径。
 *
 * <p><b>SQL 拼装方式</b>：可选条件在 Java 里逐条 append，只为真正用到的条件绑参。
 * 不写 {@code (CAST(? AS TEXT) IS NULL OR scenario = ?)} 这种「万能条件」——PostgreSQL
 * 对未使用参数的推断很容易报「无法确定参数类型」，而且 SQL 一长就看不出到底加了什么条件。
 */
@Service
public class RagTraceQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagTraceQueryService.class);

    /** 单次最多返回多少行：留痕是排查工具，不是导出功能，200 行足够看一段时间的形态。 */
    private static final int MAX_LIMIT = 200;

    private static final String BASE_COLUMNS = """
            SELECT id, trace_id, scenario, query_text, user_id, top_k, score_threshold,
                   hits, visible, returned, status, degrade_reason, elapsed_ms,
                   mode, empty_reason, to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at_text
              FROM ai_rag_traces
            """;

    private final JdbcTemplate jdbcTemplate;

    public RagTraceQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 最近若干小时的留痕，新的在前。
     *
     * @param userId   null = 管理员（看全部）
     * @param scenario 可选，CHAT / TOOL / SEARCH / REPORT
     * @param status   可选，OK / UNEXPECTED / 具体降级原因
     * @param onlyEmpty 只看「一条都没返回」的行——排查空返回率时最常用
     * @param limit    1..200
     * @param hours    回看窗口，默认 24
     */
    public List<Map<String, Object>> recent(Long userId, String scenario, String status,
                                            boolean onlyEmpty, int limit, int hours) {
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        int window = Math.max(1, Math.min(hours, 24 * 30));

        StringBuilder sql = new StringBuilder(BASE_COLUMNS);
        List<Object> args = new ArrayList<>();
        sql.append(" WHERE created_at >= now() - make_interval(hours => ?)");
        args.add(window);
        // 管理员不加 user_id 条件：null 在 SQL 里永远不等于任何值，写成 OR NULL 也能过，
        // 但那是「碰巧对」，直接不拼这一段更清楚。
        if (userId != null) {
            sql.append(" AND user_id = CAST(? AS BIGINT)");
            args.add(userId);
        }
        if (scenario != null && !scenario.isBlank()) {
            sql.append(" AND scenario = ?");
            args.add(scenario.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (onlyEmpty) {
            // 『空返回』只统计成功但 0 条的行；失败行另有 status 可筛，不该混在一起。
            sql.append(" AND status = 'OK' AND returned = 0");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(cap);

        try {
            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("traceId", rs.getString("trace_id"));
                row.put("scenario", rs.getString("scenario"));
                row.put("query", rs.getString("query_text"));
                row.put("userId", rs.getObject("user_id"));
                row.put("topK", rs.getObject("top_k"));
                row.put("scoreThreshold", rs.getObject("score_threshold"));
                row.put("hits", rs.getInt("hits"));
                row.put("visible", rs.getInt("visible"));
                row.put("returned", rs.getInt("returned"));
                row.put("status", rs.getString("status"));
                row.put("degradeReason", rs.getString("degrade_reason"));
                row.put("elapsedMs", rs.getLong("elapsed_ms"));
                row.put("mode", rs.getString("mode"));
                row.put("emptyReason", rs.getString("empty_reason"));
                row.put("createdAt", rs.getString("created_at_text"));
                return row;
            }, args.toArray());
        } catch (Exception e) {
            // 查询端点不该因为表还没建好就 500：给一条 warn，返回空列表，
            // 让调用方看到「暂时没有数据」而不是「服务挂了」。
            log.warn("[rag] 查询留痕失败（返回空列表）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按天 + 场景聚合。{@code emptyCalls} 与 {@code p95Ms} 是阶段 5 最常用的两个数：
     * 前者回答「有多少提问没查到东西」，后者回答「开精排之后慢了多少」。
     */
    public List<Map<String, Object>> daily(Long userId, int days) {
        int window = Math.max(1, Math.min(days, 90));
        StringBuilder sql = new StringBuilder("""
                SELECT to_char(created_at, 'YYYY-MM-DD') AS day,
                       scenario,
                       count(*)                                        AS calls,
                       count(*) FILTER (WHERE status = 'OK' AND returned = 0) AS empty_calls,
                       count(*) FILTER (WHERE status <> 'OK')          AS degraded_calls,
                       coalesce(round(avg(returned), 2), 0)            AS avg_returned,
                       coalesce(percentile_disc(0.95) WITHIN GROUP (ORDER BY elapsed_ms), 0) AS p95_ms
                  FROM ai_rag_traces
                 WHERE created_at >= now() - make_interval(days => ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(window);
        if (userId != null) {
            sql.append(" AND user_id = CAST(? AS BIGINT)");
            args.add(userId);
        }
        sql.append(" GROUP BY 1, 2 ORDER BY 1 DESC, 2");

        try {
            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("day", rs.getString("day"));
                row.put("scenario", rs.getString("scenario"));
                row.put("calls", rs.getLong("calls"));
                row.put("emptyCalls", rs.getLong("empty_calls"));
                row.put("degradedCalls", rs.getLong("degraded_calls"));
                row.put("avgReturned", rs.getBigDecimal("avg_returned"));
                row.put("p95Ms", rs.getLong("p95_ms"));
                return row;
            }, args.toArray());
        } catch (Exception e) {
            log.warn("[rag] 聚合留痕失败（返回空列表）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 空返回原因分布。单独一个查询而不是塞进 daily 的 GROUP BY：
     * 一旦按原因分组，daily 的「每天每场景调用数」就会被原因拆碎，两个口径混在一张表里。
     */
    public List<Map<String, Object>> emptyReasons(Long userId, int days) {
        int window = Math.max(1, Math.min(days, 90));
        StringBuilder sql = new StringBuilder("""
                SELECT empty_reason, count(*) AS calls
                  FROM ai_rag_traces
                 WHERE created_at >= now() - make_interval(days => ?)
                   AND status = 'OK' AND returned = 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(window);
        if (userId != null) {
            sql.append(" AND user_id = CAST(? AS BIGINT)");
            args.add(userId);
        }
        sql.append(" GROUP BY 1 ORDER BY 2 DESC");

        try {
            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("emptyReason", rs.getString("empty_reason"));
                row.put("calls", rs.getLong("calls"));
                return row;
            }, args.toArray());
        } catch (Exception e) {
            log.warn("[rag] 空返回原因聚合失败（返回空列表）: {}", e.getMessage());
            return List.of();
        }
    }
}
