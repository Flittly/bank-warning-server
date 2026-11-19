package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.ChineseKeywordProperties;
import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.QueryRewriteProperties;
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
 * 关键词召回（sparse）—— 混合检索的第二路。
 *
 * <p><b>两套词项，各管一类提问</b>：
 * <ul>
 *   <li><b>中文词项</b>（阶段 3）：{@link ChineseTokenizer} 把提问切成词，走
 *       {@code search_text} 上的 tsvector 倒排索引，按 {@code ts_rank_cd} 排序。
 *       解决「崩岸预警怎么分级」这类纯中文问题——改造前它们在这一路是空返回的。</li>
 *   <li><b>ASCII 锚点</b>（阶段 5）：{@code Zb} / {@code PL} / {@code 20} / {@code 50286}
 *       用 {@code ILIKE} 找字面命中。向量只知道「都讲 Zb，挺像」，不关心写的是
 *       20/30/40 中的哪一组；锚点是精确的。</li>
 * </ul>
 *
 * <p><b>两路是 OR 关系，排序不是</b>：只要中文词项或锚点命中就进候选，但
 * 「命中锚点的个数」优先于 ts_rank——锚点命中是硬证据，覆盖多个锚点的段落
 * 几乎一定是用户要的那段。
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

    /**
     * 阶段 3：中文词项的倒排索引表达式，必须与
     * {@code migration/V19__rag_chinese_search_text.sql} 里的索引表达式<b>逐字一致</b>，
     * 否则 PostgreSQL 用不上索引、退回顺序扫描。
     *
     * <p>{@code to_tsvector(regconfig, text)} 是 immutable（单参数的
     * {@code to_tsvector(text)} 是 stable，建不了索引），这里必须写两参数版本。
     * {@code search_text} 为 NULL 时整个表达式为 NULL，{@code @@} 判为 NULL（不命中），
     * 所以未回填的行自然被跳过，不需要额外的 IS NOT NULL 判断。
     */
    static final String TSV_EXPR = "to_tsvector('simple', search_text)";

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
    private final ChineseKeywordProperties chinese;
    /** 阶段 5：归一化 + 同义词扩展。关闭态是默认值，行为与阶段 3 逐字一致。 */
    private final QueryRewriteProperties rewrite;

    /** 阶段 5 及之前的调用点：中文词项默认开启，行为由 {@code hybrid-enabled} 决定。 */
    public SparseRetriever(JdbcTemplate jdbc, RagProperties props, HybridRagProperties hybrid) {
        this(jdbc, props, hybrid, ChineseKeywordProperties.enabledDefaults());
    }

    public SparseRetriever(JdbcTemplate jdbc, RagProperties props, HybridRagProperties hybrid,
                           ChineseKeywordProperties chinese) {
        this(jdbc, props, hybrid, chinese, QueryRewriteProperties.disabled());
    }

    public SparseRetriever(JdbcTemplate jdbc, RagProperties props, HybridRagProperties hybrid,
                           ChineseKeywordProperties chinese, QueryRewriteProperties rewrite) {
        this.jdbc = jdbc;
        this.props = props;
        this.hybrid = hybrid;
        this.chinese = chinese;
        this.rewrite = rewrite;
    }

    /**
     * @return 命中的段落，已按「锚点数 → ts_rank_cd」降序；两套词项都为空时返回空列表
     * @throws RuntimeException JDBC 失败或 content 不是合法 JSON，交由调用方降级
     */
    public List<Document> retrieve(String query, AiCaller who, int limit) {
        // 阶段 5：改写只作用于关键词路——向量路本来就能跨词面差异，不需要同义词表。
        // 归一化（全角/罗马数字/零宽字符）是无损的，但仍在开关里，保证关掉后行为与阶段 3 一致。
        String effectiveQuery = rewrite.enabled ? QueryNormalizer.normalize(query) : query;
        List<String> anchors = new ArrayList<>(extractAnchors(effectiveQuery, hybrid.maxAnchors));
        // 阶段 3：纯中文提问在这里第一次拿到非空词项。分词不可用时返回空，
        // 自动退回「只认锚点」，与阶段 5 行为一致。
        List<String> terms = chinese.enabled
                ? new ArrayList<>(ChineseTokenizer.terms(effectiveQuery, chinese.maxTerms))
                : new ArrayList<>();
        if (rewrite.enabled) {
            expand(effectiveQuery, anchors, terms);
        }
        String tsQuery = ChineseTokenizer.toTsQuery(terms);
        if (anchors.isEmpty() && tsQuery.isEmpty()) {
            return List.of();
        }
        if (props.permissionFilterEnabled && !who.seeAll() && who.userId() == null) {
            // 与 applyPermissionFilter 同口径的 fail-closed：宁可返回空，绝不放宽成看全部。
            log.warn("[rag] sparse 路：已认证但 userId 为空，按无权处理");
            return List.of();
        }

        List<Object> args = new ArrayList<>();
        String sql = buildSql(anchors, tsQuery, who, limit, args);
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
            String docId = rs.getString("doc_id");
            // 阶段 4：payload 解析失败一律按空 map 处理（SqlPermissionVectorSearch 同口径），
            // 来源标签会自动降级成「只有文件名」，不会因为脏 JSON 丢掉整条召回结果。
            Map<String, Object> payload =
                    SqlPermissionVectorSearch.parsePayload(rs.getString("payload"));
            DocumentMetadata meta = new DocumentMetadata(
                    TextBlock.builder().text(body).build(), docId, chunkId, payload);
            return new Document(meta);
        }, args.toArray());

        List<Document> out = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            if (doc != null) {
                out.add(doc);
            }
        }
        log.info("[rag] sparse 路：anchors={}, terms={}, hits={}, rewrite={}",
                anchors.size(), terms.size(), out.size(), rewrite.enabled ? "on" : "off");
        return out;
    }

    /**
     * 阶段 5：把同义词追加进两套词项。
     *
     * <p>标识符（{@code Zb}/{@code PL}）进锚点集合，走 ILIKE 字面匹配；
     * 中文词（「滩槽高差」）进中文词项，走倒排索引。两边各有容量上限，
     * 到了上限就停——追加词的边际收益递减，而每条 OR 都在拉长 SQL。
     */
    private void expand(String query, List<String> anchors, List<String> terms) {
        List<String> extra = QueryReformulator.expansions(query);
        if (extra.isEmpty()) {
            return;
        }
        int added = 0;
        for (String token : extra) {
            if (added >= rewrite.maxExtraTerms) {
                break;
            }
            if (QueryReformulator.isIdentifier(token)) {
                if (anchors.size() < hybrid.maxAnchors && addIgnoreCase(anchors, token)) {
                    added++;
                }
            } else if (terms.size() < chinese.maxTerms && !terms.contains(token)) {
                terms.add(token);
                added++;
            }
        }
        if (added > 0) {
            log.debug("[rag] 查询改写：追加 {} 个词项（anchors={}, terms={}）", added,
                    anchors.size(), terms.size());
        }
    }

    private static boolean addIgnoreCase(List<String> target, String token) {
        for (String existing : target) {
            if (existing.equalsIgnoreCase(token)) {
                return false;
            }
        }
        target.add(token);
        return true;
    }

    /**
     * 拼 SQL，参数按占位符出现顺序追加到 {@code args}。
     *
     * <p>结构：SELECT（rank 的 ?）→ WHERE（tsquery ? / 锚点 ?）→ 权限（user_id ?）
     * → ORDER BY（锚点 CASE ?）→ LIMIT ?。参数顺序不能重排，改了要同步改单测的
     * {@code ArgumentCaptor} 断言。
     */
    String buildSql(List<String> anchors, String tsQuery, AiCaller who, int limit, List<Object> args) {
        boolean hasTs = tsQuery != null && !tsQuery.isEmpty();
        boolean hasAnchors = anchors != null && !anchors.isEmpty();

        // payload 里带 chunkIndex / totalChunks / fileName，是「第 k/n 段」这类
        // 可追溯信息的唯一真源（阶段 4）。不取它，关键词路召回的段落在回答里就
        // 只能显示文件名、显示不出段号，与向量路不一致。
        StringBuilder sql = new StringBuilder(512)
                .append("SELECT id, doc_id, chunk_id, payload, ").append(TEXT_EXPR).append(" AS body");
        if (hasTs) {
            sql.append(", ts_rank_cd(").append(TSV_EXPR)
                    .append(", to_tsquery('simple', ?)) AS rank");
            args.add(tsQuery);
        }

        sql.append(" FROM ai_knowledge_store WHERE (");
        if (hasTs) {
            sql.append(TSV_EXPR).append(" @@ to_tsquery('simple', ?)");
            args.add(tsQuery);
        }
        if (hasAnchors) {
            if (hasTs) {
                sql.append(" OR ");
            }
            sql.append('(');
            for (int i = 0; i < anchors.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append(TEXT_EXPR).append(" ILIKE ? ESCAPE '\\'");
                args.add(like(anchors.get(i)));
            }
            sql.append(')');
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

        sql.append(" ORDER BY ");
        if (hasAnchors) {
            sql.append('(');
            for (int i = 0; i < anchors.size(); i++) {
                if (i > 0) {
                    sql.append(" + ");
                }
                sql.append("CASE WHEN ").append(TEXT_EXPR)
                        .append(" ILIKE ? ESCAPE '\\' THEN 1 ELSE 0 END");
            }
            sql.append(") DESC, ");
            for (String anchor : anchors) {
                args.add(like(anchor));
            }
        }
        if (hasTs) {
            // DESC 时 PostgreSQL 默认 NULLS FIRST，未回填 search_text 的行会排到最前，
            // 必须显式 NULLS LAST，否则「只有锚点命中」的段会盖住真正相关的段。
            sql.append("rank DESC NULLS LAST, ");
        }
        sql.append("id");

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
