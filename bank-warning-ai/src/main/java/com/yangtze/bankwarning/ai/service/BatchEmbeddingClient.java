package com.yangtze.bankwarning.ai.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 批量文本嵌入客户端 —— 入库专用，<b>不是</b> {@code EmbeddingModel} 的实现。
 *
 * <p><b>为什么必须另起一个类，而不是给 {@code EmbeddingModel} 加个方法：</b>
 * AgentScope 的 {@code EmbeddingModel} 接口只有一个
 * {@code Mono<double[]> embed(ContentBlock)}，入参单条、出参单条，接口层面就没有批量这条路。
 * 而它的实现 {@code DashScopeTextEmbedding} 内部写的是
 * {@code param.texts(List.of(text))}——把 SDK 本来能收 {@code Collection} 的能力
 * 硬包成了单元素列表。想拿回批量，只能绕开这一层封装，直接调底层
 * {@code com.alibaba.dashscope.embeddings.TextEmbedding}。
 *
 * <p>检索侧仍然走 {@code EmbeddingModel}（一次查询就一条文本，没有批量需求），
 * 本类只服务「一次上传 N 段」的入库路径。
 *
 * <p><b>三个必须做对的地方</b>：
 * <ol>
 *   <li><b>按 {@code getTextIndex()} 落位</b>，不按返回顺序 zip。服务端不保证顺序，
 *       拼错不会报错，只会让 A 段的向量挂到 B 段上——检索结果从此莫名其妙；</li>
 *   <li><b>条数不符就抛</b>。少一条不补、不多不少才对得上，静默跳过等于丢数据；</li>
 *   <li><b>只对 429 / 5xx 重试</b>。400（单条超长、参数非法）重试多少次都是 400，
 *       交给上层降级成逐条，把坏的那一条单独摘出来。</li>
 * </ol>
 *
 * <p><b>与单条路径的参数保持一致</b>：这里刻意不传 {@code textType}，
 * 因为 {@code DashScopeTextEmbedding} 也没传。参数不一致会引入额外偏差，
 * 让对拍结果失去解释力——实测中两边的差异已经全部来自「同批有邻居」
 * （详见 {@code BatchEmbeddingParityTest}），不该再叠一层参数差异。
 */
public class BatchEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(BatchEmbeddingClient.class);

    /** 单批最多试 2 次（首发 + 1 次重试）。再多只会把限流压得更死。 */
    private static final int MAX_ATTEMPTS = 2;

    private static final long RETRY_BACKOFF_MS = 500L;

    private static final String HINT =
            "批量嵌入客户端未启用：需同时满足 app.ai.rag.batch-embedding-enabled=true "
                    + "与已配置 DashScope API Key。";

    private final boolean enabled;
    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final Supplier<TextEmbedding> clientFactory;
    private final EmbeddingBatchProperties props;

    private volatile ExecutorService pool;

    private BatchEmbeddingClient(boolean enabled,
                                 String apiKey,
                                 String modelName,
                                 int dimensions,
                                 Supplier<TextEmbedding> clientFactory,
                                 EmbeddingBatchProperties props) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.clientFactory = clientFactory;
        this.props = props;
    }

    /** 关闭态：构造成功、调用即报错，与 {@code UnconfiguredEmbeddingModel} 同一取向。 */
    public static BatchEmbeddingClient disabled(EmbeddingBatchProperties props) {
        return new BatchEmbeddingClient(false, null, "unconfigured", 1024, null, props);
    }

    public static BatchEmbeddingClient dashscope(String apiKey,
                                                 String modelName,
                                                 int dimensions,
                                                 String baseUrl,
                                                 EmbeddingBatchProperties props) {
        Supplier<TextEmbedding> factory = (baseUrl == null || baseUrl.isBlank())
                ? TextEmbedding::new
                : () -> new TextEmbedding(baseUrl);
        return new BatchEmbeddingClient(true, apiKey, modelName, dimensions, factory, props);
    }

    /** 仅供单元测试注入假的 SDK 客户端，不参与生产装配。 */
    static BatchEmbeddingClient forTest(Supplier<TextEmbedding> factory,
                                        EmbeddingBatchProperties props) {
        return new BatchEmbeddingClient(true, "test-key", "text-embedding-v4", 1024, factory, props);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int batchSize() {
        return props.batchSize;
    }

    public int concurrency() {
        return props.concurrency;
    }

    public long batchTimeoutMs() {
        return props.batchTimeoutMs;
    }

    public long ingestBudgetMs() {
        return props.ingestBudgetMs;
    }

    /**
     * 一次嵌入多段文本，返回顺序与入参<b>严格一一对应</b>。
     *
     * <p>调用方须保证 {@code texts.size() <= batchSize()}，本方法不再二次切分。
     * 该方法是阻塞的，超时由调用方的 {@code Future.get} 控制。
     *
     * @throws IllegalStateException 客户端未启用、返回空、条数不符或缺少某段向量
     */
    public List<double[]> embedBatch(List<String> texts) {
        if (!enabled) {
            throw new IllegalStateException(HINT);
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        // 预检空文本：DashScope 会对整批返回 400，连带 9 条正常文本一起失败。
        // 这里提前抛，让上层降级只重试真正有问题的那一条。
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null || t.isBlank()) {
                throw new IllegalArgumentException("第 " + i + " 段文本为空，无法生成向量");
            }
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return callOnce(texts);
            } catch (RuntimeException e) {
                last = e;
                if (attempt >= MAX_ATTEMPTS || !retryable(e)) {
                    throw e;
                }
                log.warn("[rag] 批量嵌入第 {} 次失败，{}ms 后重试：{}", attempt, RETRY_BACKOFF_MS, rootMessage(e));
                sleepQuietly(RETRY_BACKOFF_MS * attempt);
            }
        }
        throw last == null ? new IllegalStateException("批量嵌入失败：未知原因") : last;
    }

    private List<double[]> callOnce(List<String> texts) {
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .dimension(dimensions)
                .texts(texts)
                .build();

        TextEmbeddingResult result;
        try {
            result = newClient().call(param);
        } catch (NoApiKeyException e) {
            // SDK 把「没有 key」设计成受检异常；对本类调用方来说它与 401 同类，
            // 都是「重试无用」的配置问题，统一转成运行时异常交给上层降级。
            throw new IllegalStateException("DashScope API Key 缺失或无效：" + e.getMessage(), e);
        }
        if (result == null || result.getOutput() == null) {
            throw new IllegalStateException("DashScope 嵌入返回空响应");
        }
        TextEmbeddingOutput output = result.getOutput();
        List<TextEmbeddingResultItem> items = output.getEmbeddings();
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("DashScope 嵌入返回空的 embeddings 列表");
        }
        if (items.size() != texts.size()) {
            throw new IllegalStateException(
                    "嵌入返回条数与请求不符：请求 " + texts.size() + " 条，返回 " + items.size() + " 条");
        }

        double[][] ordered = new double[texts.size()][];
        int fallbackIndex = 0;
        for (TextEmbeddingResultItem item : items) {
            Integer idx = item.getTextIndex();
            int slot = (idx == null) ? fallbackIndex : idx;
            fallbackIndex++;
            if (slot < 0 || slot >= texts.size()) {
                throw new IllegalStateException("嵌入返回的 textIndex 越界：" + slot);
            }
            if (ordered[slot] != null) {
                throw new IllegalStateException("嵌入返回的 textIndex 重复：" + slot);
            }
            ordered[slot] = toArray(item.getEmbedding());
        }
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                throw new IllegalStateException("第 " + i + " 段没有返回向量");
            }
        }
        return Arrays.asList(ordered);
    }

    private TextEmbedding newClient() {
        return clientFactory.get();
    }

    private static double[] toArray(List<Double> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("嵌入向量为空");
        }
        double[] out = new double[raw.size()];
        for (int i = 0; i < out.length; i++) {
            Double v = raw.get(i);
            out[i] = v == null ? 0d : v;
        }
        return out;
    }

    /**
     * 只重试「可能自己好」的错误：限流与网关/服务端 5xx。
     * 400 一类的参数错误重试无意义，直接交给上层降级逐条。
     */
    private static boolean retryable(RuntimeException e) {
        int code = statusCode(e);
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private static int statusCode(Throwable e) {
        if (e instanceof ApiException api && api.getStatus() != null) {
            return api.getStatus().getStatusCode();
        }
        return -1;
    }

    /**
     * 惰性创建线程池：关闭态与只在单测里构造的实例都不会留下空转线程。
     */
    public ExecutorService pool() {
        ExecutorService p = pool;
        if (p == null) {
            synchronized (this) {
                if (pool == null) {
                    pool = Executors.newFixedThreadPool(props.concurrency, threadFactory());
                }
                p = pool;
            }
        }
        return p;
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "rag-embed-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    public void shutdown() {
        ExecutorService p = pool;
        if (p != null) {
            p.shutdownNow();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }
}
