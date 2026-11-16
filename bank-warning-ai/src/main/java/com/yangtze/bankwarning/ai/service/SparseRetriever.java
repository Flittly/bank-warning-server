package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段 5 的关键词召回（sparse）—— 混合检索的第二路。
 *
 * <p><b>不做通用全文检索</b>：中文没有空格分词，PostgreSQL 的 {@code tsvector} 会把整句
 * 当成一个词（项目里已实测过），所以这里只抓「精确锚点」——ASCII 标识符与数字，
 * 用 {@code ILIKE '%锚点%'} 找字面命中的段落。作用是补上向量路的短板：向量只知道
 * 「都讲 Zb，挺像」，不关心写的是 20/30/40 中的哪一组。
 *
 * <p><b>一个锚点都提不出来就直接返回空、不查库</b>：纯中文语义问题（如「崩岸风险怎么判」）
 * 会自动退回 dense-only，因此开启混合检索不会改变这类问题的结果。
 *
 * <p><b>权限条件内建在 SQL 里</b>，不沿用「先召回后过滤」。否则等于把
 * {@code applyPermissionFilter()} 那个「无权正文短暂进 JVM」的弱点复制一份到第二路，
 * 而且这次是明文。调用方仍会再过滤一次（防御纵深），两次结果幂等。
 *
 * <p><b>异常一律上抛</b>，由 {@link KnowledgeService} 降级为 dense-only 并打 warn 日志。
 * 不在这里 catch 成空列表——那会让「关键词路坏了」看起来像「没有精确命中」。
 */
public class SparseRetriever {

    private static final Logger log = LoggerFactory.getLogger(SparseRetriever.class);

    /**
     * 正文表达式。{@code ai_knowledge_store.content} 存的是 SDK 写入的 JSON 包装
     * {@code {"type":"text","text":"正文…"}}，不是纯文本——实测 {@code content ILIKE '%text%'}
     * 命中 100% 的行（命中的是 JSON 键名）。所以取值与匹配都必须走 {@code ->> 'text'}，
     * V17 的 trigram 索引也建在同一表达式上，否则索引用不上。
     */
    static final String TEXT_EXPR = "(content::jsonb ->> 'text')";

    /** ASCII 标识符：Zb / PL / LC / hs / Dsed / wNM / ValueError。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,30}");

    /** 数字：20 / 30 / 42 / 13.5 / 50286。 */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * 英文停用词黑名单：这些词在中文提问里偶尔出现，但作为关键词只会召回噪音。
     * 只挡最常见的几个，不做完整 NLP——锚点提取本来就是启发式，宁缺毋滥。
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "was", "were", "what", "which", "how", "why",
            "is", "of", "to", "in", "on", "at", "by", "it", "as", "or", "be", "do", "does",
            "can", "could", "should", "would", "will", "please", "tell", "me", "about");

    private final JdbcTemplate jdbc;
    private final RagProperties props;
    private final HybridRagProperties hybrid;

    public SparseRetriever(JdbcTemplate jdbc, RagProperties props, HybridRagProperties hybrid) {
        this.jdbc = jdbc;
        this.props = props;
        this.hybrid = hybrid;
    }

    /**
     * @return 命中的段落，已按「命中锚点数」降序；无锚点或无非空正文时返回空列表
     * @throws RuntimeException JDBC 失败或 content 不是合法 JSON，交由调用方降级
     */
    public List<Document> retrieve(String query, AiCaller who, int limit) {
        List<String> anchors = extractAnchors(query, hybrid.maxAnchors);
        if (anchors.isEmpty()) {
            return List.of();
        }
        if (props.permissionFilterEnabled && !who.seeAll() && who.userId() == null) {
            // 与 applyPermissionFilter 同口径的 fail-closed：宁可返回空，绝不放宽成看全部。
            log.warn("[rag] sparse 路：已认证但 userId 为空，按无权处理");
            return List.of();
        }

        List<Object> args = new ArrayList<>();
        String sql = buildSql(anchors, who, limit, args);
        List<Document> docs = jdbc.query(sql, (rs, rowNum) -> {
            String body = rs.getString("body");
            if (body == null || body.isBlank()) {
                return null;
            }
            // SDK 的 DocumentMetadata 拒绝 null chunkId（"Chunk ID cannot be null"）。
            // 表上 chunk_id 列可空，这里用主键兜底，保证既构造得出来、又能与向量路的去重键对齐。
            String chunkId = rs.getString("chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                chunkId = rs.getString("id");
            }
            DocumentMetadata meta = DocumentMetadata.builder()
                    .content(TextBlock.builder().text(body).build())
                    .docId(rs.getString("doc_id"))
                    .chunkId(chunkId)
                    .build();
            return new Document(meta);
        }, args.toArray());

        List<Document> out = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            if (doc != null) {
                out.add(doc);
            }
        }
        log.info("[rag] sparse 路：anchors={}, hits={}", anchors.size(), out.size());
        return out;
    }

    /**
     * 拼 SQL：WHERE 若干 ILIKE（OR）→ 权限条件 → ORDER BY 命中锚点数降序 → LIMIT。
     * 参数顺序与占位符一一对应，单测用 ArgumentCaptor 断言权限片段存在。
     */
    String buildSql(List<String> anchors, AiCaller who, int limit, List<Object> args) {
        StringBuilder sql = new StringBuilder(256)
                .append("SELECT id, doc_id, chunk_id, ").append(TEXT_EXPR).append(" AS body ")
                .append("FROM ai_knowledge_store WHERE (");

        for (int i = 0; i < anchors.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append(TEXT_EXPR).append(" ILIKE ? ESCAPE '\\'");
            args.add(like(anchors.get(i)));
        }
        sql.append(')');

        if (props.permissionFilterEnabled && !who.seeAll()) {
            if (props.publicKnowledgeEnabled) {
                sql.append(" AND (user_id = CAST(? AS BIGINT) OR is_public = TRUE)");
            } else {
                sql.append(" AND user_id = CAST(? AS BIGINT)");
            }
            args.add(who.userId());
        }

        sql.append(" ORDER BY (");
        for (int i = 0; i < anchors.size(); i++) {
            if (i > 0) {
                sql.append(" + ");
            }
            sql.append("CASE WHEN ").append(TEXT_EXPR).append(" ILIKE ? ESCAPE '\\' THEN 1 ELSE 0 END");
        }
        sql.append(") DESC, id");
        for (String anchor : anchors) {
            args.add(like(anchor));
        }

        sql.append(" LIMIT ?");
        args.add(limit);
        return sql.toString();
    }

    /**
     * 从提问里抽精确锚点：先标识符（长优先）、再数字（长优先），去重后截到 max。
     *
     * <p>阈值说明：标识符要求 ≥2 字符，因为本项目要伺候的正是 {@code Zb} / {@code PL} /
     * {@code LC} / {@code hs} / {@code hc} 这类两字符代号；数字要求 ≥2 位，
     * 避免单个数字（如「3m」里的 3）把整表都召回来。
     */
    static List<String> extractAnchors(String query, int max) {
        if (query == null || query.isBlank() || max <= 0) {
            return List.of();
        }
        Map<String, String> picked = new LinkedHashMap<>();

        Matcher id = IDENTIFIER.matcher(query);
        while (id.find() && picked.size() < max) {
            String raw = id.group();
            if (STOP_WORDS.contains(raw.toLowerCase(Locale.ROOT))) {
                continue;
            }
            picked.putIfAbsent(raw.toLowerCase(Locale.ROOT), raw);
        }

        Matcher num = NUMBER.matcher(query);
        while (num.find() && picked.size() < max) {
            String raw = num.group();
            if (raw.length() < 2) {
                continue;
            }
            picked.putIfAbsent(raw.toLowerCase(Locale.ROOT), raw);
        }

        return List.copyOf(picked.values());
    }

    /** ILIKE 模式串：转义通配符，避免用户输入的 % / _ 变成「匹配任意串」。 */
    static String like(String anchor) {
        String escaped = anchor
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
