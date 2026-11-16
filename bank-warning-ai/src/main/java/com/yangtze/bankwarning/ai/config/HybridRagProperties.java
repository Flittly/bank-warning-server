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
 * <p><b>默认关闭（{@code enabled=false}）</b>：关闭时 {@code KnowledgeService.doRetrieve()}
 * 走原路径，第二路 SQL 一行都不发、RRF 一行都不执行，线上行为逐字不变。
 * 这样「代码合进仓库」与「能力通电」是两件事，可以分开决定。
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
     * 单次查询最多提取多少个精确锚点（标识符 / 数字）。
     * 每个锚点都会让 SQL 多一个 ILIKE 条件，太多会拖慢且引入噪音。
     */
    public final int maxAnchors;

    public HybridRagProperties(
            @Value("${app.ai.rag.hybrid-enabled:false}") boolean enabled,
            @Value("${app.ai.rag.hybrid-sparse-top-k:20}") int sparseTopK,
            @Value("${app.ai.rag.hybrid-rrf-k:60}") int rrfK,
            @Value("${app.ai.rag.hybrid-max-anchors:8}") int maxAnchors) {
        this.enabled = enabled;
        this.sparseTopK = sparseTopK > 0 ? sparseTopK : 20;
        this.rrfK = rrfK > 0 ? rrfK : 60;
        this.maxAnchors = maxAnchors > 0 ? maxAnchors : 8;
    }

    /**
     * 关闭态实例，供「不启动 Spring 容器」的调用方使用：
     * 单元测试的 5 参构造器、离线评测 CLI 的默认路径都走这里。
     */
    public static HybridRagProperties disabled() {
        return new HybridRagProperties(false, 20, 60, 8);
    }

    /** 与配置文件默认值一致、但强制打开的实例，供评测 CLI 的 {@code --hybrid} 使用。 */
    public static HybridRagProperties enabledWithDefaults() {
        return new HybridRagProperties(true, 20, 60, 8);
    }
}
