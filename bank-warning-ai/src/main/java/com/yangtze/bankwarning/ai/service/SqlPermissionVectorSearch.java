package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 阶段 6：把「谁能看哪些行」的判定从 Java 内存挪进向量检索 SQL 的 {@code WHERE}。
 *
 * <p><b>为什么不复用 {@code PgVectorStore.search()}：</b>SDK 的 {@code SearchDocumentDto}
 * 只有 {@code vectorName / queryEmbedding / limit / scoreThreshold} 四个字段，<b>没有 filter</b>，
 * 而 {@code PgVectorStore} 内部持有<b>一条长生命周期连接</b>（{@code private final Connection}），
 * 无法承载按请求隔离的身份。所以本类绕开 SDK 直接用 {@link JdbcTemplate}——连接由 Spring
 * 连接池按需借还，这是将来上 PostgreSQL RLS 的前置条件。
 *
 * <p><b>三份 SQL 而不是字符串拼接：</b>权限条件只有三种形态（不过滤 / 仅本人 / 本人+公共），
 * 全部写成常量，杜绝"运行时拼 WHERE 拼漏一个 AND"这类只有测试才能发现的错误。
 *
 * <p><b>分数口径：</b>{@code 1 - (embedding <=> ?)} 与 SDK
 * {@code PgVectorStore.convertDistanceToScore()} 的 COSINE 分支逐字一致（字节码为
 * {@code 1.0 - d}）。{@code scoreThreshold} 仍在本类尾部按 {@code score >= threshold} 过滤，
 * 与 SDK 在 Java 侧筛的行为一致——刻意<b>不</b>下推到 SQL，否则阈值会"补位"，
 * 导致返回条数多于旧实现，A/B 对照失去可比性。
 *
 * <p><b>{@code ORDER BY} 为什么不用别名 {@code score}：</b>HNSW/IVFFlat 只认
 * {@code ORDER BY embedding <=> ?} 这种"直接对索引列做距离运算"的形式。写成
 * {@code ORDER BY score DESC} 会退化成顺序扫描 + 排序。代价是查询向量要绑两次参数。
 *
 * <p>开关 {@code app.ai.rag.sql-permission-filter-enabled}（默认 {@code true}）关闭时，
 * {@link KnowledgeService} 退回 SDK 路径，行为与本类无关。
 */
@Component
public class SqlPermissionVectorSearch {

    private static final Logger log = LoggerFactory.getLogger(SqlPermissionVectorSearch.class);

    /** 表名与列名跟 {@code PgVectorStore} 自建表一致，故意硬编码：改表名需要同步改 SDK 配置。 */
    private static final String SELECT_FROM =
            "SELECT id, doc_id, chunk_id, content, payload, "
                    + "1 - (embedding <=> ?::vector) AS score "
                    + "FROM ai_knowledge_store ";

    /** 管理员，或 {@code permission-filter-enabled=false}：不做任何权限裁剪。 */
    private static final String SQL_NO_FILTER =
            SELECT_FROM + "ORDER BY embedding <=> ?::vector LIMIT ?";

    /** 阶段 1 的严格私有口径：只看自己名下。 */
    private static final String SQL_OWNER =
            SELECT_FROM + "WHERE user_id = CAST(? AS BIGINT) "
                    + "ORDER BY embedding <=> ?::vector LIMIT ?";

    /** 阶段 2 起的口径：自己名下 + 显式标记公共。 */
    private static final String SQL_OWNER_OR_PUBLIC =
            SELECT_FROM + "WHERE (user_id = CAST(? AS BIGINT) OR is_public = TRUE) "
                    + "ORDER BY embedding <=> ?::vector LIMIT ?";

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    @Autowired
    public SqlPermissionVectorSearch(
            JdbcTemplate jdbcTemplate,
            @Value("${app.ai.rag.sql-permission-filter-enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    /** 关闭态占位实例：不持有 JdbcTemplate，任何调用都会被 {@link #isEnabled()} 挡住。 */
    private SqlPermissionVectorSearch() {
        this.jdbcTemplate = null;
        this.enabled = false;
    }

    /**
     * 供 {@link KnowledgeService} 的兼容构造器（5/6 参，测试与 CLI 直接 {@code new}）使用。
     * 语义等价于「本开关关闭」，这些调用点的行为一个字都不变。
     */
    public static SqlPermissionVectorSearch disabled() {
        return new SqlPermissionVectorSearch();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 检索候选。返回条数上限 {@code candidates}，不做最终 {@code limit} 截断——
     * 截断由 {@link KnowledgeService} 负责，保持"候选 / 可见 / 返回"三段计数口径不变。
     *
     * <p><b>阶段 5：阈值改成入参。</b>原来本类直接读 {@code props.scoreThreshold}，
     * 于是「向量路召回底线」和「最终答案门槛」被绑成了同一个数——混合检索打开后，
     * 一条 0.3953 的真答案在粗筛就被淘汰，根本进不了融合与精排。现在由
     * {@link RagThresholdPolicy} 按档位决定传哪个值，本类只负责执行。
     *
     * <p><b>为什么还要回报被切掉的条数</b>：{@code 0 条结果}有两种完全不同的成因
     * （库里没有 / 有但被门槛切了），只看返回列表分不出来，留痕里就只能写一个含糊的
     * 「没有命中」。切掉的条数是唯一能区分两者的信息，而它只在这里存在，随手带回成本最低。
     *
     * @param queryEmbedding 查询向量
     * @param who            调用者身份；普通用户且 userId 为空时返回空（fail-closed）
     * @param props          仅用 {@code permissionFilterEnabled / publicKnowledgeEnabled}
     * @param candidates     粗召回条数
     * @param threshold      本次生效的相似度底线；{@code <= 0} 表示不设底线
     */
    public SearchResult search(double[] queryEmbedding, AiCaller who,
                               RagProperties props, int candidates, double threshold) {
        if (!enabled) {
            throw new IllegalStateException("SQL 内权限过滤已关闭，不应调用本方法");
        }
        String vector = toVectorLiteral(queryEmbedding);

        boolean needFilter = props.permissionFilterEnabled && !who.seeAll();
        Long userId = who.userId();
        if (needFilter && userId == null) {
            // 与 applyPermissionFilter 同口径：已认证但拿不到 userId 一律按无权处理。
            log.warn("[rag] 已认证但 userId 为空，SQL 内过滤按无权处理");
            return new SearchResult(List.of(), 0, 0);
        }

        String sql;
        Object[] args;
        if (!needFilter) {
            sql = SQL_NO_FILTER;
            args = new Object[]{vector, vector, candidates};
        } else if (props.publicKnowledgeEnabled) {
            sql = SQL_OWNER_OR_PUBLIC;
            args = new Object[]{vector, userId, vector, candidates};
        } else {
            sql = SQL_OWNER;
            args = new Object[]{vector, userId, vector, candidates};
        }

        List<Document> rows = jdbcTemplate.query(sql, ROW_MAPPER, args);
        List<Document> kept = applyThreshold(rows, threshold);
        return new SearchResult(kept, rows.size(), rows.size() - kept.size());
    }

    /**
     * 候选与「被门槛切掉多少」。
     *
     * @param docs              通过门槛的候选
     * @param rowsFetched       向量 SQL 实际取回的行数（门槛之前）
     * @param droppedByThreshold 被门槛切掉的行数
     */
    public record SearchResult(List<Document> docs, int rowsFetched, int droppedByThreshold) {
        public boolean isEmpty() {
            return docs.isEmpty();
        }
    }

    /** 与 SDK 一致：{@code score >= threshold} 保留，其余丢弃（而不是在 SQL 里筛后补位）。 */
    private static List<Document> applyThreshold(List<Document> rows, double threshold) {
        if (rows.isEmpty() || threshold <= 0) {
            return rows;
        }
        return rows.stream()
                .filter(doc -> {
                    Object raw = doc.getScore();
                    return raw instanceof Number n && n.doubleValue() >= threshold;
                })
                .toList();
    }

    /**
     * {@code double[]} → pgvector 文本字面量 {@code [0.1,0.2]}。
     *
     * <p>不用 {@code com.pgvector.PGvector} 对象是为了避免把 SDK 的传递依赖变成直接依赖；
     * 精度上 PG 的 {@code vector} 本身是 float4，strtod/strtof 都能解析 {@code 1.0E-5} 这类形式。
     */
    private static String toVectorLiteral(double[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10 + 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 复刻 {@code PgVectorStore.reconstructDocumentFromResult}：
     * {@code content} 是 JSON 包装（用 SDK 的 {@code JsonCodec} 反序列化，失败退回纯文本），
     * {@code payload} 是 JSON 对象，{@code chunk_id} 缺失时用 {@code doc_id#行号} 兜底
     * （{@code DocumentMetadata} 拒绝 null chunkId）。
     */
    private static final RowMapper<Document> ROW_MAPPER = (rs, rowNum) -> {
        String docId = rs.getString("doc_id");
        String chunkId = rs.getString("chunk_id");
        if (chunkId == null || chunkId.isBlank()) {
            chunkId = docId + "#" + rowNum;
        }
        ContentBlock block = parseContent(rs.getString("content"));
        Map<String, Object> payload = parsePayload(rs.getString("payload"));
        Document doc = new Document(new DocumentMetadata(block, docId, chunkId, payload));
        doc.setScore(rs.getDouble("score"));
        return doc;
    };

    private static ContentBlock parseContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return TextBlock.builder().text("").build();
        }
        try {
            ContentBlock block = JsonUtils.getJsonCodec().fromJson(raw, ContentBlock.class);
            if (block != null) {
                return block;
            }
        } catch (Exception e) {
            // 与 SDK 同口径：反序列化失败不打死检索，退回把原文当纯文本。
            log.debug("[rag] content 反序列化失败，按纯文本处理: {}", e.toString());
        }
        return TextBlock.builder().text(raw).build();
    }

    /**
     * 包级可见：{@link SparseRetriever} 也要解析同一列的 JSON 形状（阶段 4 起，关键词路
     * 召回的段落同样要能显示「第几段」，否则两路来源信息不一致）。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parsePayload(String raw) {
        if (raw == null || raw.isEmpty() || "{}".equals(raw)) {
            return Map.of();
        }
        try {
            Object parsed = JsonUtils.getJsonCodec().fromJson(raw, Map.class);
            return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            log.warn("[rag] payload 反序列化失败，按空 map 处理: {}", e.toString());
            return Map.of();
        }
    }
}
