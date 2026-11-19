package com.yangtze.bankwarning.ai.store;

import com.yangtze.bankwarning.ai.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 检索留痕存储（阶段 2）。
 *
 * <p>照 {@link JdbcReportRunStore} 的范式：启动时自举建表
 * （{@code CREATE TABLE IF NOT EXISTS}），DDL 与
 * {@code migration/V16__rag_phase2_public_and_traces.sql} 保持一致。
 *
 * <p><b>写留痕绝不能影响检索主链路</b>：开关关闭时直接返回；
 * 数据库写入异常只打 warn、不向上抛。否则「仪表盘」反而会变成新的故障点。
 *
 * <p>另外在启动期检查 {@code ai_knowledge_store.is_public} 列是否存在 ——
 * 该列由 V16 脚本手动添加（项目约定 migration 不在 Flyway 内），
 * 缺列会让公共资料 SQL 直接报错，因此这里给一条明确的 ERROR 提示，但不阻断启动。
 */
@Component
public class JdbcRagTraceStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRagTraceStore.class);

    private static final String INSERT_SQL = """
            INSERT INTO ai_rag_traces
                (trace_id, scenario, query_text, user_id, top_k, score_threshold,
                 hits, visible, returned, hits_json, status, degrade_reason, elapsed_ms,
                 mode, empty_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RagProperties props;

    public JdbcRagTraceStore(JdbcTemplate jdbcTemplate, RagProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS ai_rag_traces (
                        id               BIGSERIAL PRIMARY KEY,
                        trace_id         VARCHAR(128) NOT NULL,
                        scenario         VARCHAR(16)  NOT NULL,
                        query_text       TEXT,
                        user_id          BIGINT,
                        top_k            INT,
                        score_threshold  DOUBLE PRECISION,
                        hits             INT          NOT NULL DEFAULT 0,
                        visible          INT          NOT NULL DEFAULT 0,
                        returned         INT          NOT NULL DEFAULT 0,
                        hits_json        TEXT,
                        status           VARCHAR(24)  NOT NULL,
                        degrade_reason   TEXT,
                        elapsed_ms       BIGINT       NOT NULL DEFAULT 0,
                        created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        mode             VARCHAR(24),
                        empty_reason     VARCHAR(32)
                    )""");
            // 【阶段 5 的关键一步】上面的 CREATE TABLE IF NOT EXISTS 只对「表不存在」生效，
            // 表已经存在时它<b>整条语句被跳过</b>，新列不会补上——接着 INSERT 15 个值就会
            // 报 column "mode" does not exist，而 record() 把这个异常吞成一条 warn，
            // 于是表现是「表在、开关开着、就是永远 0 行」。这类静默失败只能靠显式 ALTER 兜住。
            jdbcTemplate.execute(
                    "ALTER TABLE ai_rag_traces ADD COLUMN IF NOT EXISTS mode VARCHAR(24)");
            jdbcTemplate.execute(
                    "ALTER TABLE ai_rag_traces ADD COLUMN IF NOT EXISTS empty_reason VARCHAR(32)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_ai_rag_traces_trace_id ON ai_rag_traces(trace_id)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_ai_rag_traces_created_at ON ai_rag_traces(created_at)");
            log.info("[rag] ai_rag_traces 表已就绪");
        } catch (Exception e) {
            // 表没建成不该让整个应用起不来；record() 里还会再次兜底。
            log.warn("[rag] ai_rag_traces 自举建表失败（留痕将不可用）: {}", e.getMessage());
        }
        checkPublicColumn();
    }

    /**
     * 只在知识表确实存在时才检查列：AgentScope 的 PgVectorStore 是外部 SDK，
     * 建表时机不受我们控制，全新库上首次启动可能还没建表，此时不应误报。
     */
    private void checkPublicColumn() {
        try {
            Boolean tableExists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass('public.ai_knowledge_store') IS NOT NULL", Boolean.class);
            if (!Boolean.TRUE.equals(tableExists)) {
                return;
            }
            Integer columns = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'ai_knowledge_store'
                       AND column_name = 'is_public'
                    """, Integer.class);
            if (columns == null || columns == 0) {
                log.error("[rag] ai_knowledge_store 缺少 is_public 列，请执行 "
                        + "migration/V16__rag_phase2_public_and_traces.sql，否则普通用户检索会失败");
            }
        } catch (Exception e) {
            log.warn("[rag] 检查 is_public 列失败（不阻断启动）: {}", e.getMessage());
        }
    }

    /** 写一行留痕。任何失败都被吞掉并记日志，检索结果不受影响。 */
    public void record(RagTrace trace) {
        if (!props.traceEnabled || trace == null) {
            return;
        }
        try {
            jdbcTemplate.update(INSERT_SQL,
                    trace.traceId(),
                    trace.scenario(),
                    trace.query(),
                    trace.userId(),
                    trace.topK(),
                    trace.scoreThreshold(),
                    trace.hits(),
                    trace.visible(),
                    trace.returned(),
                    trace.hitsJson(),
                    trace.status(),
                    trace.degradeReason(),
                    trace.elapsedMs(),
                    trace.mode(),
                    trace.emptyReason());
        } catch (Exception e) {
            log.warn("[rag] 写检索留痕失败（不影响本次检索）: traceId={}, err={}",
                    trace.traceId(), e.getMessage());
        }
    }

    /**
     * 留痕表的体检结果，供启动自检打印。任何异常都返回 {@code rows=-1}：
     * 「查不到行数」和「表里是 0 行」是两件事，启动日志里必须能区分。
     */
    public record TableStat(long rows, String lastWriteAt) {
        public boolean readable() {
            return rows >= 0;
        }
    }

    public TableStat stat() {
        try {
            Long rows = jdbcTemplate.queryForObject("SELECT count(*) FROM ai_rag_traces", Long.class);
            String last = jdbcTemplate.queryForObject(
                    "SELECT max(created_at)::text FROM ai_rag_traces", String.class);
            return new TableStat(rows == null ? 0 : rows, last);
        } catch (Exception e) {
            log.warn("[rag] 读取留痕表统计失败: {}", e.getMessage());
            return new TableStat(-1, null);
        }
    }
}
