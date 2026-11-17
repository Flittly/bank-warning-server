package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.config.UnconfiguredEmbeddingModel;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeUnavailableException.Reason;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import com.yangtze.bankwarning.ai.store.RagTrace;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库服务 —— 全系统唯一的检索入口（阶段 1 收口，阶段 2 加公共可见性与留痕）。
 *
 * <p>改造前三个调用点各自写死 {@code retrieve(query, 3|5, 0.4)}，任何规则
 * （权限、超时、阈值、降级）都要改三遍；而且所有异常被吞成空列表，
 * 造成「看起来在跑、其实没有 RAG」。现在统一负责：
 * <ol>
 *   <li>总开关与「未配置 / 无身份」的显式报错，不再静默降级；</li>
 *   <li>按场景取 topK 与阈值，参数全部来自 {@link RagProperties}；</li>
 *   <li>粗召回放大候选，按 user_id <b>或 is_public</b> 过滤后再截到 topK；</li>
 *   <li>所有外部调用带超时；</li>
 *   <li>每次检索（成功或明确不可用）写一行 {@link RagTrace}。</li>
 * </ol>
 *
 * <p><b>权限过滤现在有两个位置，由开关决定走哪一个</b>：
 * <ul>
 *   <li>{@code app.ai.rag.sql-permission-filter-enabled=true}（默认）⇒ 由
 *       {@link SqlPermissionVectorSearch} 把条件写进向量 SQL 的 {@code WHERE}，
 *       无权正文根本不会进入 JVM；</li>
 *   <li>{@code false} ⇒ 退回 SDK {@code vectorStore.search()} + {@link #applyPermissionFilter}
 *       的「先召回后过滤」，用于应急回滚，行为与阶段 4 逐字一致。</li>
 * </ul>
 * 注意 {@link #applyPermissionFilter} <b>没有删除</b>：关键词路仍自带条件后在此兜一次底，
 * 且关闭态依赖它。
 *
 * <p><b>阶段编号</b>：阶段 5 = 混合检索（dense + sparse → RRF），已实现于
 * {@link #hybridOutcome}，由 {@code app.ai.rag.hybrid-enabled} 控制，<b>默认关闭</b>：
 * 关闭时本类的执行路径、SQL 条数、日志字段与阶段 4 完全一致。
 * 阶段 6 = SQL 内权限过滤，实现在 {@link SqlPermissionVectorSearch}。
 */
@Service
@SuppressWarnings({"deprecation", "removal"})
public class KnowledgeService {

    /** 检索场景：决定默认 topK，也写进留痕便于区分来源。REPORT = 报告轨固定预检索。 */
    public enum Scenario { CHAT, TOOL, SEARCH, REPORT }

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final int MAX_CANDIDATES = 100;

    /**
     * REPORT 场景的兜底 topK。报告侧会显式传 {@code limitOverride}（优先级见 :100），
     * 这里的值实际不会被走到；但 topKFor 是穷举 switch 表达式，缺 case 会编译失败，
     * 所以必须给一个合法分支，避免报告轨配置泄漏到 RagProperties 的 11 参构造器里。
     */
    private static final int REPORT_FALLBACK_TOP_K = 3;

    /** 与 KnowledgeController 的增删改查同口径：普通用户只看自己名下的 doc_id。 */
    private static final String VISIBLE_DOC_IDS_SQL =
            "SELECT DISTINCT doc_id FROM ai_knowledge_store WHERE user_id = CAST(? AS BIGINT)";

    /** 阶段 2：额外纳入显式标记为公共的规范/规程资料。 */
    private static final String VISIBLE_DOC_IDS_WITH_PUBLIC_SQL =
            "SELECT DISTINCT doc_id FROM ai_knowledge_store "
                    + "WHERE (user_id = CAST(? AS BIGINT) OR is_public = TRUE)";

    private final EmbeddingModel embeddingModel;
    private final VDBStoreBase vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcRagTraceStore traceStore;
    private final RagProperties props;
    private final HybridRagProperties hybrid;
    private final SparseRetriever sparseRetriever;
    private final SqlPermissionVectorSearch sqlSearch;

    /**
     * 兼容既有调用点（5 处单元测试 + 离线评测 CLI 直接 {@code new}）：一律按
     * 「混合检索关闭」构造，行为与阶段 4 完全一致，这些调用点一个字都不用改。
     */
    public KnowledgeService(EmbeddingModel embeddingModel,
                            VDBStoreBase vectorStore,
                            JdbcTemplate jdbcTemplate,
                            JdbcRagTraceStore traceStore,
                            RagProperties props) {
        this(embeddingModel, vectorStore, jdbcTemplate, traceStore, props,
                HybridRagProperties.disabled());
    }

    /**
     * 兼容混合检索之前的调用点：语义等价于「阶段 6 的 SQL 内过滤关闭」，
     * 检索仍走 SDK {@code vectorStore.search()} + Java 集合过滤。
     */
    public KnowledgeService(EmbeddingModel embeddingModel,
                            VDBStoreBase vectorStore,
                            JdbcTemplate jdbcTemplate,
                            JdbcRagTraceStore traceStore,
                            RagProperties props,
                            HybridRagProperties hybrid) {
        this(embeddingModel, vectorStore, jdbcTemplate, traceStore, props,
                hybrid, SqlPermissionVectorSearch.disabled());
    }

    /**
     * Spring 实际使用的构造器。混合检索配置独立成 {@link HybridRagProperties}、
     * 阶段 6 的 SQL 内过滤独立成 {@link SqlPermissionVectorSearch}，
     * 两者的开关都不进 {@link RagProperties} 的 11 参构造器。
     */
    @Autowired
    public KnowledgeService(EmbeddingModel embeddingModel,
                            VDBStoreBase vectorStore,
                            JdbcTemplate jdbcTemplate,
                            JdbcRagTraceStore traceStore,
                            RagProperties props,
                            HybridRagProperties hybrid,
                            SqlPermissionVectorSearch sqlSearch) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.traceStore = traceStore;
        this.props = props;
        this.hybrid = hybrid;
        this.sqlSearch = sqlSearch;
        this.sparseRetriever = new SparseRetriever(jdbcTemplate, props, hybrid);
    }

    /** 兼容既有调用点；trace_id 由本方法生成。 */
    public List<Document> retrieve(String query, Scenario scenario, AiCaller caller) {
        return retrieve(query, scenario, caller, null);
    }

    /** REST 检索测试允许前端临时指定 topK；其余场景走配置。 */
    public List<Document> retrieve(String query, Scenario scenario, AiCaller caller, Integer limitOverride) {
        return retrieve(query, scenario, caller, limitOverride, null);
    }

    /**
     * 全量入口：{@code traceId} 传一次请求的编号（聊天侧传 sessionId），
     * 不传则为这次检索生成独立 UUID。留痕写入失败不影响返回值。
     */
    public List<Document> retrieve(String query, Scenario scenario, AiCaller caller,
                                   Integer limitOverride, String traceId) {
        String tid = (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
        AiCaller who = AiCaller.orAnonymous(caller);
        int limit = (limitOverride != null && limitOverride > 0) ? limitOverride : topKFor(scenario);
        long start = System.currentTimeMillis();

        try {
            Outcome outcome = doRetrieve(query, scenario, who, limit, tid);
            traceStore.record(RagTrace.success(tid, scenario.name(), query, who.userId(),
                    limit, props.scoreThreshold, outcome.hits(), outcome.visible(),
                    outcome.hitDetails(), System.currentTimeMillis() - start));
            return outcome.docs();
        } catch (KnowledgeUnavailableException e) {
            traceStore.record(RagTrace.failure(tid, scenario.name(), query, who.userId(),
                    limit, props.scoreThreshold, e.reason().name(), e.getMessage(),
                    System.currentTimeMillis() - start));
            throw e;
        } catch (RuntimeException e) {
            traceStore.record(RagTrace.failure(tid, scenario.name(), query, who.userId(),
                    limit, props.scoreThreshold, RagTrace.STATUS_UNEXPECTED, rootMessage(e),
                    System.currentTimeMillis() - start));
            throw e;
        }
    }

    /** 真正的检索过程；三段计数用于留痕，不用成员变量以免并发串数据。 */
    private Outcome doRetrieve(String query, Scenario scenario, AiCaller who, int limit, String traceId) {
        if (!props.enabled) {
            throw new KnowledgeUnavailableException(Reason.DISABLED,
                    "知识库检索已在配置中关闭（app.ai.rag.enabled=false）。");
        }

        // 工具跑在 ReAct 线程上，SecurityContext 为空；身份必须由调用方从 RuntimeContext 带进来。
        // 取不到就一律拒绝，绝不能退化成「看全部」。
        if (props.permissionFilterEnabled && !who.isAuthenticated()) {
            throw new KnowledgeUnavailableException(Reason.UNAUTHENTICATED,
                    "当前对话没有携带有效的登录身份，无法检索知识库。请重新登录后再试。");
        }

        Duration timeout = Duration.ofMillis(props.retrieveTimeoutMs);

        // 占位模型在 build 期不报错（否则应用起不来），但调用期一定失败。
        // 直接按模型名识别，比依赖 Mono.error 的异常包装方式更可靠。
        if (UnconfiguredEmbeddingModel.MODEL_NAME.equals(embeddingModel.getModelName())) {
            throw new KnowledgeUnavailableException(Reason.UNCONFIGURED,
                    "DashScope API Key 未配置，知识库（向量嵌入/检索）不可用；"
                            + "请在部署环境设置 DASHSCOPE_API_KEY 后重启服务。");
        }

        double[] queryEmbedding;
        try {
            queryEmbedding = embeddingModel
                    .embed(TextBlock.builder().text(query).build())
                    .block(timeout);
        } catch (Exception e) {
            throw new KnowledgeUnavailableException(Reason.EMBED_FAILED,
                    "生成查询向量失败：" + rootMessage(e), e);
        }
        if (queryEmbedding == null) {
            throw new KnowledgeUnavailableException(Reason.EMBED_FAILED,
                    "生成查询向量失败：嵌入模型返回空结果。");
        }

        // 粗召回比 topK 多取几倍：阶段 6 之前是为了「Java 过滤完还剩得下」，
        // 现在权限已在 SQL 里裁掉，这个后备只服务 scoreThreshold（阈值在 Java 侧筛，
        // 不补位，与 SDK 行为一致），所以参数保留、口径变了。
        int expansion = Math.max(1, props.candidateExpansion);
        int candidates = Math.min(MAX_CANDIDATES, Math.max(limit, limit * expansion));

        // 阶段 6：开关打开 ⇒ 权限条件进 SQL，返回的 dense 已经是「自己有权看的」。
        boolean denseFiltered = sqlSearch.isEnabled();
        List<Document> dense;
        if (denseFiltered) {
            try {
                dense = sqlSearch.search(queryEmbedding, who, props, candidates);
            } catch (Exception e) {
                throw new KnowledgeUnavailableException(Reason.SEARCH_FAILED,
                        "知识库检索失败：" + rootMessage(e), e);
            }
        } else {
            SearchDocumentDto searchDto = SearchDocumentDto.builder()
                    .queryEmbedding(queryEmbedding)
                    .limit(candidates)
                    .scoreThreshold(props.scoreThreshold)
                    .build();
            List<Document> hits;
            try {
                hits = vectorStore.search(searchDto).block(timeout);
            } catch (Exception e) {
                throw new KnowledgeUnavailableException(Reason.SEARCH_FAILED,
                        "知识库检索失败：" + rootMessage(e), e);
            }
            dense = hits == null ? List.of() : hits;
        }

        if (hybrid.enabled) {
            return hybridOutcome(query, scenario, who, limit, traceId, dense, denseFiltered);
        }

        // 关闭态：与阶段 4 逐字一致（含空结果提前返回与日志字段），保证「零回归」可验证。
        if (dense.isEmpty()) {
            return new Outcome(List.of(), 0, 0, List.of());
        }
        List<Document> visible = denseFiltered ? dense : applyPermissionFilter(dense, who);
        List<Document> result = visible.stream().limit(limit).toList();
        log.info("[rag] traceId={}, scenario={}, hits={}, visible={}, returned={}, topK={}",
                traceId, scenario, dense.size(), visible.size(), result.size(), limit);
        return new Outcome(result, dense.size(), visible.size(), toHits(result));
    }

    /**
     * 阶段 5：向量路 + 关键词路 → RRF 融合。
     *
     * <p>三条行为约定：
     * <ol>
     *   <li>关键词路<b>自带权限条件</b>，这里仍再过滤一次（幂等），保证即使 SQL 拼装出错
     *       也不会把无权正文交给模型；</li>
     *   <li>关键词路抛异常时<b>降级为 dense-only 并打 warn</b>，不打死原本可用的检索；
     *       降级痕迹同时出现在日志里，避免重演阶段 1 之前的静默失败；</li>
     *   <li>融合后 {@code Document.getScore()} 的语义从余弦相似度变成无量纲 RRF 分，
     *       留痕表结构与列不变，口径见 {@code docs/rag-hybrid-phase5-design.md}。</li>
     * </ol>
     */
    private Outcome hybridOutcome(String query, Scenario scenario, AiCaller who, int limit,
                                  String traceId, List<Document> denseHits, boolean denseFiltered) {
        // 阶段 6 打开时 dense 已在 SQL 内裁过，这里再筛一次即可（幂等，成本是一条轻量 SQL）；
        // 关闭时维持阶段 5 的原有行为，逐字不变。
        List<Document> denseVisible = denseFiltered ? denseHits : applyPermissionFilter(denseHits, who);

        List<Document> sparseHits;
        try {
            sparseHits = sparseRetriever.retrieve(query, who, hybrid.sparseTopK);
        } catch (Exception e) {
            log.warn("[rag] traceId={}, sparse 路失败，本次降级为 dense-only: {}",
                    traceId, rootMessage(e));
            sparseHits = List.of();
        }
        List<Document> sparseVisible = sparseHits.isEmpty()
                ? List.of() : applyPermissionFilter(sparseHits, who);

        List<Document> fused = RrfFusion.fuse(denseVisible, sparseVisible, hybrid.rrfK, limit);
        log.info("[rag] traceId={}, scenario={}, hybrid=on, dense={}, denseVisible={}, "
                        + "sparse={}, sparseVisible={}, fused={}, topK={}",
                traceId, scenario, denseHits.size(), denseVisible.size(),
                sparseHits.size(), sparseVisible.size(), fused.size(), limit);
        return new Outcome(fused, denseHits.size() + sparseHits.size(),
                denseVisible.size() + sparseVisible.size(), toHits(fused));
    }

    private static List<RagTrace.Hit> toHits(List<Document> docs) {
        List<RagTrace.Hit> details = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            Object rawScore = doc.getScore();
            Double score = rawScore instanceof Number n ? n.doubleValue() : null;
            details.add(new RagTrace.Hit(
                    String.valueOf(doc.getMetadata().getDocId()),
                    String.valueOf(doc.getId()),
                    score));
        }
        return details;
    }

    /**
     * 权限裁剪：管理员看全部；普通用户保留「自己名下 + 显式标记公共」的 doc_id。
     * 查不出可见集合时一律抛异常（fail-closed），绝不返回未过滤结果。
     *
     * <p>{@code is_public} 列由 V16 脚本添加；关掉
     * {@code app.ai.rag.public-knowledge-enabled} 就退回阶段 1 的严格私有口径，
     * 也可用于「列还没加」时的应急回滚。
     */
    private List<Document> applyPermissionFilter(List<Document> hits, AiCaller who) {
        if (!props.permissionFilterEnabled || who.seeAll()) {
            return hits;
        }
        Long userId = who.userId();
        if (userId == null) {
            log.warn("[rag] 已认证但 userId 为空，按无权处理");
            return List.of();
        }
        String sql = props.publicKnowledgeEnabled
                ? VISIBLE_DOC_IDS_WITH_PUBLIC_SQL : VISIBLE_DOC_IDS_SQL;
        Set<String> visibleDocIds;
        try {
            visibleDocIds = new HashSet<>(
                    jdbcTemplate.queryForList(sql, String.class, userId));
        } catch (Exception e) {
            throw new KnowledgeUnavailableException(Reason.SEARCH_FAILED,
                    "知识库权限校验失败：" + rootMessage(e), e);
        }
        return hits.stream()
                .filter(doc -> visibleDocIds.contains(doc.getMetadata().getDocId()))
                .toList();
    }

    private int topKFor(Scenario scenario) {
        return switch (scenario) {
            case CHAT -> props.chatTopK;
            case TOOL -> props.toolTopK;
            case SEARCH -> props.searchTopK;
            case REPORT -> REPORT_FALLBACK_TOP_K;
        };
    }

    private record Outcome(List<Document> docs, int hits, int visible,
                           List<RagTrace.Hit> hitDetails) {}

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }

    /**
     * 逐段生成向量，失败的那一段跳过，只把成功的段写进库。
     *
     * <p>改造前 catch 里只打日志，最后仍无条件 {@code vectorStore.add(docs)}，
     * 会把没有向量的段也写进去（或整批失败），且无法区分成功与失败。
     */
    public Mono<Void> addDocuments(List<Document> docs) {
        return Mono.fromRunnable(() -> {
            Duration timeout = Duration.ofMillis(props.retrieveTimeoutMs);
            List<Document> ready = new ArrayList<>();
            int failed = 0;
            for (Document doc : docs) {
                try {
                    double[] embedding = embeddingModel
                            .embed(doc.getMetadata().getContent())
                            .block(timeout);
                    if (embedding == null) {
                        failed++;
                        log.warn("[rag] 嵌入结果为空，跳过该段: docId={}, id={}",
                                doc.getMetadata().getDocId(), doc.getId());
                        continue;
                    }
                    doc.setEmbedding(embedding);
                    ready.add(doc);
                } catch (Exception e) {
                    failed++;
                    log.error("[rag] 单段嵌入失败，跳过: docId={}, id={}",
                            doc.getMetadata().getDocId(), doc.getId(), e);
                }
            }
            if (ready.isEmpty()) {
                log.warn("[rag] 全部 {} 段嵌入失败，未写入向量库", failed);
                return;
            }
            vectorStore.add(ready).block(timeout);
            log.info("[rag] 写入 {} 段，跳过失败 {} 段", ready.size(), failed);
        });
    }
}
