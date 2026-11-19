package com.yangtze.bankwarning.ai.config;

import com.yangtze.bankwarning.ai.service.RagThresholdPolicy;
import com.yangtze.bankwarning.ai.service.RerankClient;
import com.yangtze.bankwarning.ai.service.SqlPermissionVectorSearch;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 5：启动时把「知识库检索到底按什么在跑」打成一张表。
 *
 * <p><b>为什么需要它</b>：这一轮之前的真实事故是「代码里全都有、线上一样没生效」——
 * 混合检索与精排的实现都已合入，但两个开关默认 {@code false}、{@code application.yml}
 * 里连键都没写，于是评测报告上的 0.8421 与线上跑的 0.6842 并存了整整一个阶段，
 * 而<b>没有任何一处日志能看出这件事</b>。开关越多，这种「静默降级」越难发现。
 *
 * <p><b>为什么不做成 profile 配置项</b>：那会制造两种真源（布尔开关 + profile 名），
 * 迟早出现「profile 说开了、布尔其实关了」。这里只做<b>只读派生</b>：布尔仍是唯一真源，
 * 本类负责把它翻译成一句人话，并在组合明显不合理时打 warn。
 *
 * <p><b>绝不阻断启动</b>：所有检查都包在 try/catch 里，连数据库不可达也只打一行 warn。
 * 一个「体检报告」如果把服务弄挂了，比不做体检更糟。
 */
@Component
public class RagStartupReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagStartupReport.class);

    private final RagProperties props;
    private final HybridRagProperties hybrid;
    private final RerankProperties rerankProps;
    private final RagThresholdProperties thresholdProps;
    private final RerankClient rerankClient;
    private final SqlPermissionVectorSearch sqlSearch;
    private final ChineseKeywordProperties chinese;
    private final QueryRewriteProperties rewrite;
    private final JdbcRagTraceStore traceStore;
    private final JdbcTemplate jdbcTemplate;

    public RagStartupReport(RagProperties props,
                            HybridRagProperties hybrid,
                            RerankProperties rerankProps,
                            RagThresholdProperties thresholdProps,
                            RerankClient rerankClient,
                            SqlPermissionVectorSearch sqlSearch,
                            ChineseKeywordProperties chinese,
                            QueryRewriteProperties rewrite,
                            JdbcRagTraceStore traceStore,
                            JdbcTemplate jdbcTemplate) {
        this.props = props;
        this.hybrid = hybrid;
        this.rerankProps = rerankProps;
        this.thresholdProps = thresholdProps;
        this.rerankClient = rerankClient;
        this.sqlSearch = sqlSearch;
        this.chinese = chinese;
        this.rewrite = rewrite;
        this.traceStore = traceStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            RagThresholdPolicy.Thresholds th = RagThresholdPolicy.of(props, hybrid,
                    rerankClient.isEnabled(), rerankProps.scoreThreshold, thresholdProps);
            JdbcRagTraceStore.TableStat stat = props.traceEnabled ? traceStore.stat() : null;
            boolean searchText = hasSearchTextColumn();

            log.info("[rag] ===== 知识库检索生效配置 =====");
            log.info("[rag] 档位     : {}{}", th.mode(), props.enabled ? "" : "（检索总开关已关闭）");
            log.info("[rag] 阈值口径 : {}", th.reason());
            log.info("[rag] topK     : chat={}, tool={}, search={}",
                    props.chatTopK, props.toolTopK, props.searchTopK);
            log.info("[rag] 候选     : 扩张 {}x，融合池 {}，精排池 {}，精排超时 {}ms",
                    props.candidateExpansion, hybrid.candidatePool,
                    rerankProps.candidatePool, rerankProps.timeoutMs);
            log.info("[rag] 关键词路 : jieba={}, search_text 列={}, 查询改写={}",
                    chinese.enabled ? "on" : "off", searchText ? "存在" : "缺失",
                    rewrite.enabled ? "on（同义词 " + rewrite.maxExtraTerms + " 个上限）" : "off");
            log.info("[rag] 权限     : 过滤={}, 公共资料={}, SQL 内过滤={}",
                    props.permissionFilterEnabled ? "on" : "off",
                    props.publicKnowledgeEnabled ? "on" : "off",
                    sqlSearch.isEnabled() ? "on" : "off（回退到 Java 过滤）");
            log.info("[rag] 留痕     : {}", traceLine(stat));
            for (String w : warnings(searchText)) {
                log.warn("[rag] [warn] {}", w);
            }
            log.info("[rag] ================================");
        } catch (Exception e) {
            log.warn("[rag] 启动自检打印失败（不影响服务）: {}", e.getMessage());
        }
    }

    private String traceLine(JdbcRagTraceStore.TableStat stat) {
        if (!props.traceEnabled) {
            return "关闭（不写 ai_rag_traces）";
        }
        if (stat == null || !stat.readable()) {
            return "开启，但读不到表状态（检查 ai_rag_traces 是否建好）";
        }
        return "开启，已有 " + stat.rows() + " 行"
                + (stat.lastWriteAt() == null ? "，尚无写入" : "，最近写入 " + stat.lastWriteAt());
    }

    /**
     * 组合检查。<b>只警告，不改行为、不拒绝启动</b>：这些组合都有合法用途
     * （比如临时关掉精排省钱），把它们变成启动失败等于用工具限制了运维。
     *
     * <p>抽成包级方法是为了能单测「什么组合该报什么」，而不是去断言日志字符串。
     */
    List<String> warnings(boolean searchTextExists) {
        List<String> out = new ArrayList<>();
        if (!props.enabled) {
            out.add("检索总开关 app.ai.rag.enabled=false：所有知识库调用都会明确报「已关闭」");
        }
        if (!props.traceEnabled) {
            out.add("留痕关闭：空返回率、降级次数、p95 延迟都无从观察，排障只能靠日志");
        }
        if (!hybrid.enabled) {
            out.add("混合检索关闭：线上走纯向量档，与已发布的评测报告（hybrid+rerank）不是同一套配置");
        }
        if (hybrid.enabled && chinese.enabled && !searchTextExists) {
            out.add("ai_knowledge_store 缺少 search_text 列：关键词路会恒空，"
                    + "请执行 migration/V19__rag_chinese_search_text.sql 并跑 SearchTextBackfillCli");
        }
        if (rerankProps.enabled && !rerankClient.isEnabled()) {
            out.add("rerank 配了开启但客户端不可用（通常是 DASHSCOPE_API_KEY 未配置），"
                    + "实际会按粗排顺序返回");
        }
        if (rerankClient.isEnabled()) {
            int maxTopK = Math.max(props.chatTopK, Math.max(props.toolTopK, props.searchTopK));
            if (rerankProps.candidatePool <= maxTopK) {
                out.add("精排候选池 " + rerankProps.candidatePool + " <= 最大 topK " + maxTopK
                        + "：重排没有可重排的余地，只会在同样几条里换顺序");
            }
        }
        if (!sqlSearch.isEnabled() && props.permissionFilterEnabled) {
            out.add("SQL 内权限过滤关闭：权限回退到「先召回后过滤」，被过滤的正文会短暂进入 JVM");
        }
        return out;
    }

    /** 只在知识表存在时检查列：新库首启时 SDK 可能还没建表，此时不该误报缺列。 */
    private boolean hasSearchTextColumn() {
        try {
            Boolean tableExists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass('public.ai_knowledge_store') IS NOT NULL", Boolean.class);
            if (!Boolean.TRUE.equals(tableExists)) {
                return false;
            }
            Integer n = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'ai_knowledge_store'
                       AND column_name = 'search_text'
                    """, Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            log.warn("[rag] 检查 search_text 列失败: {}", e.getMessage());
            return false;
        }
    }
}
