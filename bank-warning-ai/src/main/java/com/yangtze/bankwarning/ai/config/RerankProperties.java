package com.yangtze.bankwarning.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阶段 8：rerank（二阶段精排）的独立配置。
 *
 * <p>与 {@link HybridRagProperties} 一样刻意不进 {@link RagProperties} 的 11 参构造器，
 * 避免连带破坏 7 处直接 {@code new} 的调用点。
 *
 * <p><b>阶段 5 起默认开启</b>：rerank 每次检索多一次外部 HTTP 往返（典型 100~300ms），
 * 且按调用量计费，所以必须同时满足三个前提：有 {@code rerank-timeout-ms} 兜底、
 * 失败整段降级、以及开关能一行关掉。关闭时检索路径一行都不多走，行为退回阶段 7。
 *
 * <p><b>为什么 rerank 值得多一次往返</b>：RRF 是「排名融合」，不是「相关性判断」——
 * 它只比较两路各自给了第几名，从不读 query 与 doc 的具体内容。两阶段召回
 * （粗排取池 → 精排取 topK）补的正是这一步：先用便宜的方式把范围从「全库」缩到
 * 几十条，再用贵的 cross-encoder 逐对打分。
 */
@Component
public final class RerankProperties {

    /** 总开关。false = 完全不走 rerank，检索路径与阶段 7 一致。 */
    public final boolean enabled;

    /** 精排模型。DashScope 目前为 {@code gte-rerank-v2}。 */
    public final String model;

    /**
     * 送进 rerank 的候选池大小。<b>必须明显大于最终 topK</b>，
     * 否则「重排」没有可重排的余地，只会在同样几条里换个顺序。
     */
    public final int candidatePool;

    /**
     * 精排阶段的软门槛：relevanceScore 低于此值的候选被丢弃，避免把不相关段硬塞进 topK。
     * 0 = 不过滤（先取满 topK）。<b>默认 0</b>：rerank 分数的分布需要先用评测标定，
     * 没标定就给阈值等于凭空制造一个不可解释的口径。
     */
    public final double scoreThreshold;

    /**
     * 可选的指令前缀（部分 rerank 模型支持按任务定制打分口径）。
     * 空串 = 不传，走模型默认行为。
     */
    public final String instruct;

    /**
     * 阶段 5：单次精排的硬上限（毫秒）。<b>默认 3000</b>，{@code <= 0} 表示不设超时（不推荐）。
     *
     * <p><b>为什么必须单独给一个超时</b>：{@code app.ai.rag.retrieve-timeout-ms} 只包住了
     * 向量嵌入那一步（{@code embed(...).block(timeout)}），而精排是<b>同步</b>调用——
     * 精排一旦变慢，卡住的是整个对话线程，连超时兜底都没有。上一阶段它默认关闭，
     * 所以这个问题没暴露；默认打开之前必须先有刹车，否则一次外部抖动就是一次全站卡死。
     */
    public final long timeoutMs;

    public RerankProperties(
            @Value("${app.ai.rag.rerank-enabled:true}") boolean enabled,
            @Value("${app.ai.rag.rerank-model:gte-rerank-v2}") String model,
            @Value("${app.ai.rag.rerank-candidate-pool:30}") int candidatePool,
            @Value("${app.ai.rag.rerank-score-threshold:0.0}") double scoreThreshold,
            @Value("${app.ai.rag.rerank-instruct:}") String instruct,
            @Value("${app.ai.rag.rerank-timeout-ms:3000}") long timeoutMs) {
        this.enabled = enabled;
        this.model = (model == null || model.isBlank()) ? "gte-rerank-v2" : model;
        // 池子至少 2，否则“重排”退化成一个元素排序；上限与粗召回硬上限对齐。
        this.candidatePool = Math.max(2, Math.min(candidatePool, 100));
        this.scoreThreshold = scoreThreshold > 0 ? scoreThreshold : 0.0;
        this.instruct = instruct == null ? "" : instruct;
        this.timeoutMs = timeoutMs;
    }

    /** 关闭态，供单元测试与离线 CLI 默认路径使用。 */
    public static RerankProperties disabled() {
        return new RerankProperties(false, "gte-rerank-v2", 30, 0.0, "", 3000);
    }

    /** 强制打开、其余取默认值，供评测 CLI 的 {@code --rerank} 使用。 */
    public static RerankProperties enabledWithDefaults() {
        return new RerankProperties(true, "gte-rerank-v2", 30, 0.0, "", 3000);
    }

    /** 评测用：指定池大小与阈值。 */
    public static RerankProperties of(int candidatePool, double scoreThreshold) {
        return new RerankProperties(true, "gte-rerank-v2", candidatePool, scoreThreshold, "", 3000);
    }
}
