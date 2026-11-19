package com.yangtze.bankwarning.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阶段 5 混合检索（dense 向量 + sparse 关键词 → RRF）的独立配置。
 *
 * <p><b>刻意不塞进 {@link RagProperties}</b>：后者是 11 参构造器，被 7 处
 * （6 处测试 + {@code defaults()}）直接 {@code new}，加参数会让这批调用同时编译失败。
 * 同样的做法已在 {@link ReportRagProperties} 上验证过。
 *
 * <p><b>阶段 5 起默认开启</b>：关闭时 {@code KnowledgeService.doRetrieve()} 走原路径，
 * 第二路 SQL 一行都不发、RRF 一行都不执行，行为退化为纯向量档——这是应急回滚姿势。
 * 之所以敢默认开：同一份 52 题考卷上 hybrid+rerank 把 Recall@5 从 0.6842 抬到 0.8421，
 * 而开关留在关闭态时那 0.16 的收益用户一次都拿不到。
 *
 * <p>打开前请确认三件事：① 知识库已有足够多的不同 {@code doc_id}（否则两路都只能
 * 召回同一篇，融合结果与 dense-only 无差别，测不出收益）；② 已手动执行
 * {@code migration/V17__rag_hybrid_content_trgm.sql}；③ 跑过一次 off/on 对照评测。
 */
@Component
public final class HybridRagProperties {

    /** 混合检索开关。false = 只走向量一路（阶段 4 及之前的行为）。 */
    public final boolean enabled;

    /** 关键词路最多取回多少段候选，再交给 RRF 与向量路合并。 */
    public final int sparseTopK;

    /**
     * RRF 的平滑常数 k。业界常用 60；值越大，排名靠后与靠前的权重差越小。
     * 本项目不做调参：语料规模化后按实测再定，不要凭感觉改。
     */
    public final int rrfK;

    /**
     * 阶段 8：两路 RRF 权重。<b>默认 1.0 / 1.0，与阶段 5 的等权逐位一致</b>，
     * 打开混合检索但不动权重时，行为与改造前完全相同。
     *
     * <p>为什么需要权重：向量路衡量「语义相近」，关键词路衡量「字面命中锚点数」，
     * 两路的可靠度在中文长文档场景并不对等——纯语义提问时关键词路只能靠 ILIKE
     * 撞上噪声段，等权会把它和向量路同票计入。权重应由离线评测的格点搜索决定，
     * 不要凭感觉设。
     */
    public final double denseWeight;

    public final double sparseWeight;

    /**
     * 阶段 8：融合前的候选池大小。<b>0 = 沿用旧逻辑</b>（{@code limit * candidateExpansion}），
     * 非 0 时取 {@code max(旧逻辑, 本值)} 并夹到 {@link #MAX_CANDIDATE_POOL}。
     *
     * <p>为什么需要它：RRF 的收益取决于两路候选的<b>重叠度</b>。池子太小（如 top-3 场景
     * 只取 15 条）时，两路往往召回同一批段，融合退化成几乎无变化。给融合与 rerank
     * 一个独立的池子，才能让「重排」有可重排的东西。
     */
    public final int candidatePool;

    /**
     * 候选池上限，与 {@code KnowledgeService.MAX_CANDIDATES} 保持一致：
     * 那边是粗召回的硬上限，这里配更大也取不到，写在类里只是为了配错时早暴露。
     */
    public static final int MAX_CANDIDATE_POOL = 100;

    /**
     * 单次查询最多提取多少个精确锚点（标识符 / 数字）。
     * 每个锚点都会让 SQL 多一个 ILIKE 条件，太多会拖慢且引入噪音。
     */
    public final int maxAnchors;

    public HybridRagProperties(
            @Value("${app.ai.rag.hybrid-enabled:true}") boolean enabled,
            @Value("${app.ai.rag.hybrid-sparse-top-k:20}") int sparseTopK,
            @Value("${app.ai.rag.hybrid-rrf-k:60}") int rrfK,
            @Value("${app.ai.rag.hybrid-max-anchors:8}") int maxAnchors,
            @Value("${app.ai.rag.hybrid-dense-weight:1.0}") double denseWeight,
            @Value("${app.ai.rag.hybrid-sparse-weight:1.0}") double sparseWeight,
            @Value("${app.ai.rag.hybrid-candidate-pool:30}") int candidatePool) {
        this.enabled = enabled;
        this.sparseTopK = sparseTopK > 0 ? sparseTopK : 20;
        this.rrfK = rrfK > 0 ? rrfK : 60;
        this.maxAnchors = maxAnchors > 0 ? maxAnchors : 8;
        // 负数没有语义，按「不改权重」处理；0 会把一路彻底抹掉，属于需要显式配置的取值。
        this.denseWeight = denseWeight >= 0 ? denseWeight : 1.0;
        this.sparseWeight = sparseWeight >= 0 ? sparseWeight : 1.0;
        this.candidatePool = candidatePool > 0
                ? Math.min(candidatePool, MAX_CANDIDATE_POOL) : 0;
    }

    /**
     * 关闭态实例，供「不启动 Spring 容器」的调用方使用：
     * 单元测试的 5 参构造器、离线评测 CLI 的默认路径都走这里。
     */
    public static HybridRagProperties disabled() {
        return new HybridRagProperties(false, 20, 60, 8, 1.0, 1.0, 0);
    }

    /** 与配置文件默认值一致、但强制打开的实例，供评测 CLI 的 {@code --hybrid} 使用。 */
    public static HybridRagProperties enabledWithDefaults() {
        return new HybridRagProperties(true, 20, 60, 8, 1.0, 1.0, 0);
    }

    /**
     * 阶段 8：带权重 / 候选池的实例，供评测 CLI 做融合策略对照。
     * 权重传负数按 1.0 处理，与构造器口径一致。
     */
    public static HybridRagProperties withWeights(double denseWeight, double sparseWeight,
                                                  int candidatePool) {
        return new HybridRagProperties(true, 20, 60, 8,
                denseWeight, sparseWeight, candidatePool);
    }
}
