package com.yangtze.bankwarning.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 入库侧批量嵌入的独立配置。
 *
 * <p><b>刻意不塞进 {@link RagProperties}</b>：后者是 11 参构造器，被 6 处测试与
 * {@code RagEvalCli} 直接 {@code new}，加参数会让这批调用同时编译失败。
 * 与 {@link HybridRagProperties}、{@link ReportRagProperties} 同一处置惯例。
 *
 * <p><b>默认关闭（{@code enabled=false}）</b>：关闭时 {@code KnowledgeService.addDocuments}
 * 走原来的「逐段 embed + block」循环，线上行为逐字不变。这样「代码进仓库」与
 * 「批量能力通电」是两件事，可以分开决定。
 *
 * <p><b>打开前先看 {@code BatchEmbeddingParityTest} 的实测结果</b>（2026-11-17，
 * {@code text-embedding-v4}，4 段中文语料）：
 * <pre>
 *   同一路径重复调用：      差 0.0e+00   ← 服务端完全确定，不是随机抖动
 *   embed() vs texts[1]：   差 0.0e+00   ← 只装 1 条时两条路径完全一致
 *   texts[1] vs texts[4]：  差 1.5e-4 ~ 4.8e-4，余弦 0.9999987
 * </pre>
 * 也就是说：<b>数值差来自「同批有邻居」</b>，即 batch&gt;1 时服务端走了不同的批内计算路径，
 * 与「走哪个 SDK 方法」无关。据此有两条档位，改配置即可切换、代码不用动：
 * <ul>
 *   <li><b>零差异档</b>：{@code batch-size=1} + {@code batch-concurrency=4}。
 *       请求数不变（100 段仍是 100 次），但并发 4 路，总耗时降到约 1/4，
 *       向量与改造前逐位相同，适合「不想动数据口径」的场景；</li>
 *   <li><b>高吞吐档</b>：{@code batch-size=10} + {@code batch-concurrency=4}。
 *       请求数 100→10，总耗时降到约 1/10~1/40，代价是每条向量有 1e-4 级数值差
 *       （余弦 0.9999987）。检索排序在分数差小于 1e-5 时才可能受影响。</li>
 * </ul>
 *
 * <p>五个参数各自的理由：
 * <ol>
 *   <li>{@code batchSize}：DashScope {@code text-embedding-v4} 单次上限 10 条，
 *       配大了直接 400，所以这里硬夹到 {@link #MAX_BATCH_SIZE}，不做「配多少用多少」；</li>
 *   <li>{@code concurrency}：4 而不是 16。瓶颈在服务端的 RPM 限流，不在本地线程，
 *       并发上去先撞 429，重试反而更慢；</li>
 *   <li>{@code batchTimeoutMs}：单批的等待上限，替代原来「每段 15s」的口径；</li>
 *   <li>{@code ingestBudgetMs}：一次上传的<b>总预算</b>，防止「每批都没超时、加起来
 *       远超反向代理超时」——原来 100 段 × 15s = 25 分钟就是这么来的。</li>
 * </ol>
 */
@Component
public final class EmbeddingBatchProperties {

    /** DashScope 文本嵌入接口的硬上限，超过即 400，不是可调参数。 */
    public static final int MAX_BATCH_SIZE = 10;

    /** 入库批量嵌入总开关。false = 逐段串行（改造前的行为）。 */
    public final boolean enabled;

    /** 每批多少条，上限 {@link #MAX_BATCH_SIZE}。 */
    public final int batchSize;

    /** 并行批次数。 */
    public final int concurrency;

    /** 单批超时（毫秒），同时用作写向量库的超时。 */
    public final long batchTimeoutMs;

    /** 一次入库的总预算（毫秒），超出后剩余批次放弃并计入失败。 */
    public final long ingestBudgetMs;

    public EmbeddingBatchProperties(
            @Value("${app.ai.rag.batch-embedding-enabled:false}") boolean enabled,
            @Value("${app.ai.rag.batch-size:10}") int batchSize,
            @Value("${app.ai.rag.batch-concurrency:4}") int concurrency,
            @Value("${app.ai.rag.batch-timeout-ms:30000}") long batchTimeoutMs,
            @Value("${app.ai.rag.ingest-budget-ms:180000}") long ingestBudgetMs) {
        this.enabled = enabled;
        this.batchSize = batchSize <= 0 ? MAX_BATCH_SIZE : Math.min(batchSize, MAX_BATCH_SIZE);
        this.concurrency = concurrency <= 0 ? 4 : concurrency;
        this.batchTimeoutMs = batchTimeoutMs <= 0 ? 30_000L : batchTimeoutMs;
        this.ingestBudgetMs = ingestBudgetMs <= 0 ? 180_000L : ingestBudgetMs;
    }

    /** 关闭态实例，供不启动 Spring 容器的调用方（单元测试、离线评测）使用。 */
    public static EmbeddingBatchProperties disabled() {
        return new EmbeddingBatchProperties(false, MAX_BATCH_SIZE, 4, 30_000L, 180_000L);
    }

    /** 与配置文件默认值一致、但强制打开，供对拍探针与评测使用。 */
    public static EmbeddingBatchProperties enabledWithDefaults() {
        return new EmbeddingBatchProperties(true, MAX_BATCH_SIZE, 4, 30_000L, 180_000L);
    }
}
