package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.ChineseKeywordProperties;
import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.QueryRewriteProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.config.RagThresholdProperties;
import com.yangtze.bankwarning.ai.config.RerankProperties;
import com.yangtze.bankwarning.ai.config.UnconfiguredEmbeddingModel;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeUnavailableException.Reason;
import com.yangtze.bankwarning.ai.service.SqlPermissionVectorSearch.SearchResult;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import com.yangtze.bankwarning.ai.store.RagEmptyReason;
import com.yangtze.bankwarning.ai.store.RagTrace;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * 阶段 3（进阶）= 中文关键词路，{@link SparseRetriever} 用 jieba 词项查
 * {@code search_text} 倒排索引；入库后由 {@link #refreshSearchText} 补写该列。
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

    /**
     * 阶段二：删除某文档的上一版，范围与删除接口同口径（本人名下；userId 为空 = 管理员删全部）。
     * 不限定归属的话，两个用户用同一标题上传时，后传的会把先传的整篇删掉。
     */
    private static final String DELETE_OWN_DOC_SQL =
            "DELETE FROM ai_knowledge_store WHERE doc_id = ? AND (user_id = ? OR ?::bigint IS NULL)";

    private final EmbeddingModel embeddingModel;
    private final VDBStoreBase vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcRagTraceStore traceStore;
    private final RagProperties props;
    private final HybridRagProperties hybrid;
    private final SparseRetriever sparseRetriever;
    private final SqlPermissionVectorSearch sqlSearch;
    private final BatchEmbeddingClient batchClient;
    /** 阶段 8：rerank 精排。关闭态对象非 null，{@code isEnabled()} 返回 false。 */
    private final RerankClient rerankClient;
    /** 阶段 3（进阶）：中文关键词路配置。关闭时 sparse 路退回纯 ASCII 锚点。 */
    private final ChineseKeywordProperties chinese;
    /** 阶段 5：向量路召回底线。只在混合/精排档生效，纯向量档仍用 {@code props.scoreThreshold}。 */
    private final RagThresholdProperties thresholdProps;

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
     * 阶段 6 及之前的调用点：等价于「入库批量嵌入关闭」，{@code addDocuments}
     * 走逐段串行循环，行为与改造前逐字一致。
     */
    public KnowledgeService(EmbeddingModel embeddingModel,
                            VDBStoreBase vectorStore,
                            JdbcTemplate jdbcTemplate,
                            JdbcRagTraceStore traceStore,
                            RagProperties props,
                            HybridRagProperties hybrid,
                            SqlPermissionVectorSearch sqlSearch) {
        this(embeddingModel, vectorStore, jdbcTemplate, traceStore, props,
                hybrid, sqlSearch, BatchEmbeddingClient.disabled(EmbeddingBatchProperties.disabled()));
    }

    /**
     * 阶段 7 及之前的调用点：等价于「rerank 关闭」，检索结果与阶段 7 逐字一致。
     * 阶段 3 的中文词项按默认开启传入——它只影响混合检索打开时的 sparse 路，
     * 而这些调用点的 {@code hybrid} 恒为关闭态，行为不变。
     */
    public KnowledgeService(EmbeddingModel embeddingModel,
                            VDBStoreBase vectorStore,
                            JdbcTemplate jdbcTemplate,
                            JdbcRagTraceStore traceStore,
                            RagProperties props,
                            HybridRagProperties hybrid,
                            SqlPermissionVectorSearch sqlSearch,
                            BatchEmbeddingClient batchClient) {
        this(embeddingModel, vectorStore, jdbcTemplate, traceStore, props,
                hybrid, sqlSearch, batchClient,
                RerankClient.disabled(RerankProperties.disabled()),
                ChineseKeywordProperties.enabledDefaults(),
                RagThresholdProperties.legacy(),
                QueryRewriteProperties.disabled());
    }

    /**
     * Spring 实际使用的构造器。混合检索配置独立成 {@link HybridRagProperties}、
     * 阶段 6 的 SQL 内过滤独立成 {@link SqlPermissionVectorSearch}、
     * 阶段 7 的入库批量嵌入独立成 {@link BatchEmbeddingClient}、
     * 阶段 8 的精排独立成 {@link RerankClient}、
     * 阶段 5（进阶）的召回底线独立成 {@link RagThresholdProperties}，
     * 五者的开关都不进 {@link RagProperties} 的 11 参构造器。
     */
    @Autowired
    public KnowledgeService(EmbeddingModel embeddingModel,
                            VDBStoreBase vectorStore,
                            JdbcTemplate jdbcTemplate,
                            JdbcRagTraceStore traceStore,
                            RagProperties props,
                            HybridRagProperties hybrid,
                            SqlPermissionVectorSearch sqlSearch,
                            BatchEmbeddingClient batchClient,
                            RerankClient rerankClient,
                            ChineseKeywordProperties chinese,
                            RagThresholdProperties thresholdProps,
                            QueryRewriteProperties rewriteProps) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.traceStore = traceStore;
        this.props = props;
        this.hybrid = hybrid;
        this.sqlSearch = sqlSearch;
        this.batchClient = batchClient;
        this.rerankClient = rerankClient;
        this.chinese = chinese;
        this.thresholdProps = thresholdProps;
        this.sparseRetriever = new SparseRetriever(jdbcTemplate, props, hybrid, chinese, rewriteProps);
        if (chinese.enabled) {
            // 阶段 3：jieba 词典约 2 MB，首次分词 0.6~1.2 s。放在构造期预热，
            // 不让第一个提问的用户承担这份开销；失败也只是少一路召回，不影响启动。
            ChineseTokenizer.warmUp();
        }
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
     *
     * <p>阶段 5 起改为交给 {@link #retrieveDetailed}：调用方若关心「为什么一条都没返回」，
     * 用那个方法拿原因；只关心结果的既有调用点继续用本方法，行为逐字不变。
     */
    public List<Document> retrieve(String query, Scenario scenario, AiCaller caller,
                                   Integer limitOverride, String traceId) {
        return retrieveDetailed(query, scenario, caller, limitOverride, traceId).docs();
    }

    /**
     * 检索结果 + 空返回原因。
     *
     * @param docs        返回给调用方的段落（最多 topK 条）
     * @param emptyReason 有结果时为 null；空列表时为 {@link RagEmptyReason} 的 {@code code()}。
     *                    工具层据此告诉模型「是没这条规范」还是「是门槛/权限挡的」——
     *                    这两种情况对用户要说的话完全不同。
     */
    public record RetrieveResult(List<Document> docs, String emptyReason) {
        public boolean isEmpty() {
            return docs.isEmpty();
        }
    }

    public RetrieveResult retrieveDetailed(String query, Scenario scenario, AiCaller caller,
                                           Integer limitOverride, String traceId) {
        String tid = (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
        AiCaller who = AiCaller.orAnonymous(caller);
        int limit = (limitOverride != null && limitOverride > 0) ? limitOverride : topKFor(scenario);
        long start = System.currentTimeMillis();
        RagThresholdPolicy.Thresholds th = thresholds();

        try {
            Outcome outcome = doRetrieve(query, scenario, who, limit, tid, th);
            String empty = emptyReasonOf(outcome);
            traceStore.record(RagTrace.success(tid, scenario.name(), query, who.userId(),
                    limit, th.recallFloor(), outcome.hits(), outcome.visible(),
                    toHits(outcome.docs()), System.currentTimeMillis() - start,                    th.mode(), empty));
            return new RetrieveResult(outcome.docs(), empty);
        } catch (KnowledgeUnavailableException e) {
            traceStore.record(RagTrace.failure(tid, scenario.name(), query, who.userId(),
                    limit, th.recallFloor(), e.reason().name(), e.getMessage(),
                    System.currentTimeMillis() - start, th.mode()));
            throw e;
        } catch (RuntimeException e) {
            traceStore.record(RagTrace.failure(tid, scenario.name(), query, who.userId(),
                    limit, th.recallFloor(), RagTrace.STATUS_UNEXPECTED, rootMessage(e),
                    System.currentTimeMillis() - start, th.mode()));
            throw e;
        }
    }

    /** 阶段 5：当前档位与两把尺子。每次检索算一次是纯函数，成本可忽略。 */
    private RagThresholdPolicy.Thresholds thresholds() {
        return RagThresholdPolicy.of(props, hybrid, rerankClient.isEnabled(),
                rerankClient.properties().scoreThreshold, thresholdProps);
    }

    /**
     * 空返回原因：四种成因要做的事完全不同，所以必须分开记。
     *
     * <p>{@code hits} 是<b>门槛之前</b>取回的候选数，{@code visible} 是权限之后的可见数。
     * 没有前者就分不出「库里没有」与「全被门槛切了」——这两种在改造前都是同一个空数组。
     */
    private static String emptyReasonOf(Outcome o) {
        if (!o.docs().isEmpty()) {
            return null;
        }
        if (o.hits() == 0) {
            return o.droppedByThreshold() > 0
                    ? RagEmptyReason.BELOW_RECALL_FLOOR.code()
                    : RagEmptyReason.NO_CANDIDATE.code();
        }
        if (o.visible() == 0) {
            return RagEmptyReason.PERMISSION_TRIMMED.code();
        }
        return RagEmptyReason.BELOW_FINAL_FLOOR.code();
    }

    /** 真正的检索过程；三段计数用于留痕，不用成员变量以免并发串数据。 */
    private Outcome doRetrieve(String query, Scenario scenario, AiCaller who, int limit, String traceId,
                               RagThresholdPolicy.Thresholds th) {
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
        // 阶段 8：融合与 rerank 需要有「可重排的东西」。候选池配 0 时沿用上式，
        // 行为与阶段 7 逐字一致；配了正数才抬高粗召回量（仍受 MAX_CANDIDATES 约束）。
        if (hybrid.enabled && hybrid.candidatePool > 0) {
            candidates = Math.min(MAX_CANDIDATES, Math.max(candidates, hybrid.candidatePool));
        }

        // 阶段 8：rerank 打开时，粗召回必须比它自己的池子更大，否则「精排」没有候选可选。
        if (rerankClient.isEnabled()) {
            candidates = Math.min(MAX_CANDIDATES,
                    Math.max(candidates, rerankClient.candidatePool()));
        }

        // 阶段 6：开关打开 ⇒ 权限条件进 SQL，返回的 dense 已经是「自己有权看的」。
        // 阶段 5：门槛不再固定 0.4，由 RagThresholdPolicy 按档位给——纯向量档仍是 0.4，
        // 混合/精排档降到 recall-floor（默认 0），把「相关不相关」的判断留给后面两层。
        boolean denseFiltered = sqlSearch.isEnabled();
        List<Document> dense;
        int denseRows;
        int droppedByThreshold;
        if (denseFiltered) {
            SearchResult result;
            try {
                result = sqlSearch.search(queryEmbedding, who, props, candidates, th.recallFloor());
            } catch (Exception e) {
                throw new KnowledgeUnavailableException(Reason.SEARCH_FAILED,
                        "知识库检索失败：" + rootMessage(e), e);
            }
            dense = result.docs();
            denseRows = result.rowsFetched();
            droppedByThreshold = result.droppedByThreshold();
        } else {
            SearchDocumentDto searchDto = SearchDocumentDto.builder()
                    .queryEmbedding(queryEmbedding)
                    .limit(candidates)
                    .scoreThreshold(th.recallFloor())
                    .build();
            List<Document> hits;
            try {
                hits = vectorStore.search(searchDto).block(timeout);
            } catch (Exception e) {
                throw new KnowledgeUnavailableException(Reason.SEARCH_FAILED,
                        "知识库检索失败：" + rootMessage(e), e);
            }
            dense = hits == null ? List.of() : hits;
            // SDK 只返回筛过的列表，被切掉多少无从得知：这里填 0 表示「不知道」，
            // 空返回原因因此会落到 NO_CANDIDATE 而不是 BELOW_RECALL_FLOOR。
            // 这是应急回滚路径（sql-permission-filter-enabled=false）才有的精度损失。
            denseRows = dense.size();
            droppedByThreshold = 0;
        }

        if (hybrid.enabled) {
            return hybridOutcome(query, scenario, who, limit, traceId, dense, denseFiltered,
                    denseRows, droppedByThreshold);
        }

        // 关闭态：rerank 也关闭时与阶段 4 逐字一致（含空结果提前返回与日志字段），
        // 保证「零回归」可验证；rerank 打开时这里多一道精排，其余不变。
        if (dense.isEmpty()) {
            return new Outcome(List.of(), denseRows, 0, droppedByThreshold);
        }
        List<Document> visible = denseFiltered ? dense : applyPermissionFilter(dense, who);
        List<Document> result = rerankOrHead(query, visible, limit, traceId);
        log.info("[rag] traceId={}, scenario={}, hits={}, visible={}, returned={}, topK={}",
                traceId, scenario, dense.size(), visible.size(), result.size(), limit);
        return new Outcome(result, denseRows, visible.size(), droppedByThreshold);
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
     *       这是第三种量纲，不能和 0.4 那把余弦尺子比；阶段 5 起留痕多记 {@code mode}，
     *       就是为了让「这一次用的是哪把尺子」可查。</li>
     * </ol>
     */
    private Outcome hybridOutcome(String query, Scenario scenario, AiCaller who, int limit,
                                  String traceId, List<Document> denseHits, boolean denseFiltered,
                                  int denseRows, int droppedByThreshold) {
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

        // rerank 打开时先融合到池子大小、再精排到 limit；关闭时直接截到 limit，
        // 与阶段 5/7 的返回逐字一致。
        int fusionLimit = rerankClient.isEnabled()
                ? Math.max(limit, rerankClient.candidatePool()) : limit;
        List<Document> fused = RrfFusion.fuse(denseVisible, sparseVisible, hybrid.rrfK, fusionLimit,
                hybrid.denseWeight, hybrid.sparseWeight);
        List<Document> result = rerankOrHead(query, fused, limit, traceId);
        log.info("[rag] traceId={}, scenario={}, hybrid=on, dense={}, denseVisible={}, "
                        + "sparse={}, sparseVisible={}, fused={}, returned={}, topK={}, weights={}/{}",
                traceId, scenario, denseHits.size(), denseVisible.size(),
                sparseHits.size(), sparseVisible.size(), fused.size(), result.size(), limit,
                hybrid.denseWeight, hybrid.sparseWeight);
        return new Outcome(result, denseRows + sparseHits.size(),
                denseVisible.size() + sparseVisible.size(), droppedByThreshold);
    }

    /**
     * 阶段 8：精排最后一道。关闭或失败时都退回「粗排顺序 + 截断」，
     * 保证 rerank 是<b>增益</b>而不是新的失败点。
     */
    private List<Document> rerankOrHead(String query, List<Document> candidates, int limit, String traceId) {
        if (!rerankClient.isEnabled()) {
            return candidates.stream().limit(limit).toList();
        }
        try {
            return rerankClient.rerank(query, candidates, limit);
        } catch (Exception e) {
            log.warn("[rag] traceId={}, rerank 失败，本次退回粗排顺序: {}", traceId, rootMessage(e));
            return candidates.stream().limit(limit).toList();
        }
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

    /**
     * 一次检索的三段计数。
     *
     * @param docs               最终返回的段落
     * @param hits               门槛之前取回的候选总数（向量路原始行数 + 关键词路条数）
     * @param visible            权限过滤后仍可见的条数
     * @param droppedByThreshold 被向量路召回路底线切掉的行数；0 同时表示「没切」与「SDK 路径不知道」
     */
    private record Outcome(List<Document> docs, int hits, int visible, int droppedByThreshold) {}

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }

    /**
     * 入库结果。存在的理由：原 {@code addDocuments} 返回 {@code Mono<Void>}，
     * 调用方只能报 {@code success:true}——「全部段嵌入失败」也被报成成功。
     * 现在把成功/失败段数交回 Controller，前端才可能看见真实状态。
     */
    public record IngestResult(int total, int ok, int failed) {
        public boolean allOk() {
            return failed == 0;
        }
    }

    /**
     * 生成向量并写库；失败的那一段跳过，只把成功的段写进库。
     *
     * <p>保留该签名不动（Controller 与实际调用点共 2 处、测试 2 处），
     * 需要失败明细的调用方改用 {@link #addDocumentsWithResult}。
     */
    public Mono<Void> addDocuments(List<Document> docs) {
        return addDocumentsWithResult(docs).then();
    }

    /**
     * 阶段 7：入库路径的批量版本。
     *
     * <p>两条互斥路径，由 {@code app.ai.rag.batch-embedding-enabled} 决定：
     * <ul>
     *   <li><b>关闭</b> ⇒ {@link #ingestSequentially}，逐段 {@code embed().block()}，
     *       与改造前逐字一致，用于回滚；</li>
     *   <li><b>打开</b> ⇒ {@link #ingestBatched}，10 条一批、4 路并发。
     *       原来是「100 段 = 100 次请求 × 串行等」，现在「100 段 = 10 批 ÷ 4 并发 ≈ 3 轮」。</li>
     * </ul>
     */
    public Mono<IngestResult> addDocumentsWithResult(List<Document> docs) {
        return Mono.fromCallable(() -> batchClient.isEnabled()
                ? ingestBatched(docs)
                : ingestSequentially(docs));
    }

    /**
     * 阶段二：把一批「临时 docId」的行换成正式 docId，同时删掉该文档的上一版。
     *
     * <p><b>为什么不在入库前先删旧行</b>：删了之后嵌入失败就两头空（旧的没了、新的没成）。
     * 这里让整批先写在临时 id 上，全部嵌入成功后才进这个事务；事务里只有「删旧 + 换名」
     * 两条语句，中间不存在「文档暂时不存在」的窗口（外部看不到未提交状态）。
     * 写入失败时只会留下 staging 残行，由 {@link #discardStaged} 清掉，旧版本完好。
     *
     * <p><b>换名一行都没改到</b>（staging 被并发清掉、或写库其实没成功）必须抛异常：
     * 继续往下走就是「旧版被删、新版没进来」，而日志里只会看到一条 success。
     *
     * @param stagingDocId 临时 id，形如 {@code 标题#stg-xxxx}
     * @param finalDocId   正式 doc_id（用户在列表里看到的那个）
     * @param ownerUserId  调用者 userId；{@code null} = 管理员，删该 doc_id 下全部旧行
     */
    @Transactional
    public void replaceStaged(String stagingDocId, String finalDocId, Long ownerUserId) {
        int deleted = jdbcTemplate.update(DELETE_OWN_DOC_SQL, finalDocId, ownerUserId, ownerUserId);
        int promoted = jdbcTemplate.update(
                "UPDATE ai_knowledge_store SET doc_id = ? WHERE doc_id = ?", finalDocId, stagingDocId);
        if (promoted == 0) {
            throw new IllegalStateException("入库换名失败：临时批次不存在 " + stagingDocId);
        }
        log.info("[rag] 入库换名：docId={}, 删除旧段={}, 迁入新段={}", finalDocId, deleted, promoted);
    }

    /**
     * 阶段 3：为某篇文档的所有段补写 {@code search_text}（jieba 词项，空格分隔）。
     *
     * <p><b>为什么不做在 {@link #replaceStaged} 的事务里</b>：换名事务只管「删旧 + 迁入」。
     * 一旦分词或 UPDATE 抛错（最典型的是新机器还没执行 V19，列不存在），
     * PostgreSQL 会把整个事务标记为 aborted，连入库一起回滚。而 search_text 只是
     * 关键词路的加速列，缺了它这篇文档仍能被向量路召回——不该让它有否决入库的权力。
     *
     * <p>因此本方法是 <b>best-effort</b>：异常一律吞掉并打 warn，返回成功写入的行数。
     * 幂等，可反复调用；灌库与回填脚本也走它。
     */
    public int refreshSearchText(String docId) {
        if (docId == null || docId.isBlank()) {
            return 0;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, " + SparseRetriever.TEXT_EXPR + " AS body "
                            + "FROM ai_knowledge_store WHERE doc_id = ?", docId);
            List<Object[]> batch = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Object body = row.get("body");
                String searchText = ChineseTokenizer.indexText(body == null ? null : body.toString());
                if (!searchText.isEmpty()) {
                    batch.add(new Object[]{searchText, row.get("id")});
                }
            }
            if (batch.isEmpty()) {
                return 0;
            }
            jdbcTemplate.batchUpdate(
                    "UPDATE ai_knowledge_store SET search_text = ? WHERE id = ?", batch);
            log.info("[rag] search_text 已刷新：docId={}, 段数={}", docId, batch.size());
            return batch.size();
        } catch (RuntimeException e) {
            log.warn("[rag] search_text 刷新失败（该篇暂不参与中文关键词路，确认已执行 V19）："
                    + "docId={}, 原因={}", docId, e.toString());
            return 0;
        }
    }

    /**
     * 阶段二：整批作废。只删临时 docId 的行，<b>永远不碰正式文档</b>。
     *
     * @return 清掉的残行数（正常语义下应为「本次写入的段数」）
     */
    public int discardStaged(String stagingDocId) {
        int removed = jdbcTemplate.update(
                "DELETE FROM ai_knowledge_store WHERE doc_id = ?", stagingDocId);
        log.info("[rag] 丢弃临时批次：docId={}, 清理残行={}", stagingDocId, removed);
        return removed;
    }

    /** 改造前的逐段串行循环，逻辑与日志一字未改，只多返回一个计数。 */
    private IngestResult ingestSequentially(List<Document> docs) {
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
            return new IngestResult(docs.size(), 0, docs.size());
        }
        vectorStore.add(ready).block(timeout);
        log.info("[rag] 写入 {} 段，跳过失败 {} 段", ready.size(), failed);
        return new IngestResult(docs.size(), ready.size(), docs.size() - ready.size());
    }

    /**
     * 批量入库：按 {@code batchSize} 切片 → 线程池并发 → 汇总 → 一次性写向量库。
     *
     * <p>两层时间约束必须同时存在，缺一个都会重演「一个上传卡 25 分钟」：
     * <ul>
     *   <li>单批：{@link BatchEmbeddingClient#batchTimeoutMs()}，等一批最多多久；</li>
     *   <li>总预算：{@link BatchEmbeddingClient#ingestBudgetMs()}，一次上传所有批加起来的上限。
     *       只设单批不设总预算是没用的——100 批每批都「没超时」，加起来照样爆掉。</li>
     * </ul>
     */
    private IngestResult ingestBatched(List<Document> docs) {
        int total = docs.size();
        if (total == 0) {
            return new IngestResult(0, 0, 0);
        }

        int size = Math.max(1, batchClient.batchSize());
        List<List<Document>> batches = new ArrayList<>((total + size - 1) / size);
        for (int i = 0; i < total; i += size) {
            batches.add(docs.subList(i, Math.min(total, i + size)));
        }

        long deadline = System.currentTimeMillis() + batchClient.ingestBudgetMs();
        List<Document> ready = Collections.synchronizedList(new ArrayList<>(total));

        List<Future<?>> futures = new ArrayList<>(batches.size());
        for (List<Document> batch : batches) {
            futures.add(batchClient.pool().submit(() -> embedBatchInto(batch, ready)));
        }

        boolean budgetExhausted = false;
        for (Future<?> f : futures) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                budgetExhausted = true;
                f.cancel(true);
                continue;
            }
            try {
                f.get(remain, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                budgetExhausted = true;
                f.cancel(true);
            } catch (ExecutionException e) {
                // embedBatchInto 自己吞掉业务异常并逐条降级，走到这里说明是 Error 级别
                log.error("[rag] 嵌入批次任务异常", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                break;
            }
        }
        if (budgetExhausted) {
            log.error("[rag] 入库总预算 {}ms 用尽，剩余批次已放弃", batchClient.ingestBudgetMs());
        }

        int ok = ready.size();
        if (ok == 0) {
            log.warn("[rag] 全部 {} 段嵌入失败，未写入向量库", total);
            return new IngestResult(total, 0, total);
        }
        try {
            vectorStore.add(ready).block(Duration.ofMillis(batchClient.batchTimeoutMs()));
        } catch (Exception e) {
            log.error("[rag] 写入向量库失败，本次入库未生效", e);
            return new IngestResult(total, 0, total);
        }
        log.info("[rag] 批量入库 {} 段：成功 {}，失败 {}（{} 条/批，{} 并发，{} 批）",
                total, ok, total - ok, size, batchClient.concurrency(), batches.size());
        return new IngestResult(total, ok, total - ok);
    }

    /**
     * 嵌入一批。整批失败时<b>降级为逐条重试</b>，只丢真正有问题的那一条。
     *
     * <p>这不是可选项：批量把「10 条文本」绑成了一个请求，只要其中一条超过
     * 8192 token 或命中内容审核，整批就 400。不降级的话，一条坏文本能让另外 9 条
     * 正常段落一起入库失败——可用性比批量之前更差。
     */
    private void embedBatchInto(List<Document> batch, List<Document> ready) {
        List<String> texts = new ArrayList<>(batch.size());
        for (Document d : batch) {
            texts.add(textOf(d));
        }
        try {
            List<double[]> vectors = batchClient.embedBatch(texts);
            for (int i = 0; i < batch.size(); i++) {
                Document doc = batch.get(i);
                doc.setEmbedding(vectors.get(i));
                ready.add(doc);
            }
        } catch (Exception e) {
            log.warn("[rag] {} 条一批嵌入失败，降级逐条重试：{}", batch.size(), rootMessage(e));
            for (int i = 0; i < batch.size(); i++) {
                Document doc = batch.get(i);
                try {
                    List<double[]> one = batchClient.embedBatch(List.of(texts.get(i)));
                    doc.setEmbedding(one.get(0));
                    ready.add(doc);
                } catch (Exception e2) {
                    log.error("[rag] 单段嵌入失败，跳过: docId={}, id={}",
                            doc.getMetadata().getDocId(), doc.getId(), e2);
                }
            }
        }
    }

    private static String textOf(Document doc) {
        ContentBlock block = doc.getMetadata().getContent();
        if (block instanceof TextBlock tb) {
            return tb.getText();
        }
        return block == null ? "" : String.valueOf(block);
    }
}
