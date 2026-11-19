package com.yangtze.bankwarning.ai.service;

import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankParam;
import com.alibaba.dashscope.rerank.TextReRankResult;
import com.yangtze.bankwarning.ai.config.RerankProperties;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 阶段 8：rerank 二阶段精排客户端。
 *
 * <p><b>它补的是哪一步</b>：混合检索用 RRF 把两路「排名」合成一份名单，但 RRF
 * 从头到尾没读过 query 和候选的具体内容——它只知道「向量路给了第几名、关键词路给了第几名」。
 * rerank 用 cross-encoder 把 (query, doc) 成对送进模型打分，才第一次真正判断相关性。
 * 代价是每次检索多一次 HTTP 往返，所以只在候选池（几十条）上做，不做全库。
 *
 * <p><b>安全前提：调用方必须在此之前完成权限过滤。</b>rerank 会把候选正文发给外部服务，
 * 把无权文档送出去等于绕过整条 security trimming 的设计。本类不做也不该做权限判断——
 * 它拿到什么就送什么，所以位置必须由 {@code KnowledgeService} 保证。
 *
 * <p><b>三个必须做对的点</b>：
 * <ol>
 *   <li><b>按 {@code getIndex()} 回填，不按返回顺序 zip</b>：服务端返回的顺序不等于
 *       输入顺序，也不是分数降序的保证；按位置拼会把 A 的分数挂到 B 上，
 *       这种错不报错，只会让排序莫名其妙；</li>
 *   <li><b>不原地 {@code setScore}</b>：同一 {@link Document} 可能在一次请求里被复用，
 *       原地改写会让调用方的对象带上精排分（RRF 那次踩过同样的坑）；</li>
 *   <li><b>失败整段降级，不部分返回</b>：只拿到一半结果就截断，等于用残缺名单回答，
 *       比直接退回融合顺序更糟。</li>
 * </ol>
 */
public final class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    /** null = 未启用（开关关闭或 key 缺失），此时 {@link #rerank} 原样截断。 */
    private final TextReRank sdk;
    private final String apiKey;
    private final RerankProperties props;

    private RerankClient(TextReRank sdk, String apiKey, RerankProperties props) {
        this.sdk = sdk;
        this.apiKey = apiKey;
        this.props = props;
    }

    /** 未启用：行为等价于「rerank 这一层不存在」。 */
    public static RerankClient disabled(RerankProperties props) {
        return new RerankClient(null, null, props == null ? RerankProperties.disabled() : props);
    }

    /**
     * 构造真实客户端。
     *
     * <p><b>必须用无参 {@code new TextReRank()}，把 apiKey 放进 param。</b>
     * SDK 的 {@code TextReRank(String)} 单参构造器参数是<b>协议名</b>（"http" / "websocket"），
     * 不是 apiKey——把 key 传进去会让 {@code Protocol.of(key)} 返回 null，
     * 报一个跟鉴权毫无关系的 {@code ServiceOption.getProtocol() is null}。
     * 这个重载陷阱在编译期完全看不出来，只能靠真跑一次才会暴露。
     */
    public static RerankClient dashscope(String apiKey, RerankProperties props) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("rerank 需要 DashScope apiKey");
        }
        return new RerankClient(new TextReRank(), apiKey.trim(), props);
    }

    /** 供测试注入 mock，避免真实网络调用。 */
    public static RerankClient forTest(Supplier<TextReRank> factory, RerankProperties props) {
        return new RerankClient(factory.get(), "test-key", props);
    }

    public boolean isEnabled() {
        return sdk != null && props.enabled;
    }

    /** 送进 rerank 的候选池大小；调用方据此决定粗召回要取多少条。 */
    public int candidatePool() {
        return props.candidatePool;
    }

    /**
     * 对候选重排。
     *
     * @param query       原始用户问题（不是改写后的查询——rerank 要判断的是「这段能不能回答用户」）
     * @param candidates  已排序的候选（融合结果或单路结果），内部只用前 {@code candidatePool} 条
     * @param limit       最终返回条数
     * @return 新的 Document 副本列表，score 为 rerank 相关性分；未启用时原样截断
     */
    public List<Document> rerank(String query, List<Document> candidates, int limit) {
        int cap = limit > 0 ? limit : 1;
        if (!isEnabled() || candidates == null || candidates.size() <= 1) {
            return head(candidates, cap);
        }

        int poolSize = Math.min(candidates.size(), props.candidatePool);
        List<Document> pool = candidates.subList(0, poolSize);

        List<String> texts = new ArrayList<>(poolSize);
        for (Document doc : pool) {
            String text = textOf(doc);
            // 空文本会让服务端直接拒整个请求。这里选择整体降级而不是跳过该条：
            // 跳过会打乱 index 映射，而映射一旦错位就是「静默错误排序」。
            if (text.isBlank()) {
                log.warn("[rag] rerank 跳过：候选池含空文本（docId={}），本次退回融合顺序",
                        doc.getMetadata() == null ? "?" : doc.getMetadata().getDocId());
                return head(candidates, cap);
            }
            texts.add(text);
        }

        List<TextReRankResultItem> items = callSdk(query, texts);
        List<Document> out = new ArrayList<>(Math.min(cap, items.size()));
        for (TextReRankResultItem item : items) {
            if (out.size() >= cap) {
                break;
            }
            double score = item.score();
            if (props.scoreThreshold > 0 && score < props.scoreThreshold) {
                continue;
            }
            Document src = pool.get(item.index());
            Document copy = new Document(src.getMetadata());
            copy.setScore(score);
            out.add(copy);
        }
        log.info("[rag] rerank 完成：pool={}, 输入={}, 返回={}, 最终={}, threshold={}",
                props.candidatePool, texts.size(), items.size(), out.size(), props.scoreThreshold);
        return out;
    }

    /** 调 SDK 并把结果整理成「(原下标, 分数)」的降序列表；任何异常都向上抛，由调用方降级。 */
    private List<TextReRankResultItem> callSdk(String query, List<String> texts) {
        TextReRankParam.TextReRankParamBuilder<?, ?> builder = TextReRankParam.builder();
        // 逐句设置而不链式调用：builder 的泛型是通配符，链式会把返回类型收敛成 capture，
        // 后续方法无法解析。这不是风格问题，是能否编译通过的问题。
        builder.apiKey(apiKey);
        builder.model(props.model);
        builder.query(query);
        builder.documents(texts);
        // topN 取候选数而不是最终 limit：开了 scoreThreshold 时，先砍到 limit 条再过滤
        // 会凑不满数。让服务端把所有候选都打上分，截断与过滤在本地做，口径更可控。
        builder.topN(texts.size());
        builder.returnDocuments(false);
        if (!props.instruct.isBlank()) {
            builder.instruct(props.instruct);
        }

        TextReRankResult result;
        try {
            result = sdk.call(builder.build());
        } catch (Exception e) {
            throw new IllegalStateException("rerank 调用失败：" + rootMessage(e), e);
        }
        if (result == null || result.getOutput() == null
                || result.getOutput().getResults() == null
                || result.getOutput().getResults().isEmpty()) {
            throw new IllegalStateException("rerank 返回空结果");
        }

        List<TextReRankResultItem> items = new ArrayList<>(result.getOutput().getResults().size());
        for (com.alibaba.dashscope.rerank.TextReRankOutput.Result r : result.getOutput().getResults()) {
            Integer idx = r.getIndex();
            Double score = r.getRelevanceScore();
            if (idx == null || idx < 0 || idx >= texts.size()) {
                // 越界 index 说明响应结构变了，继续用会读到别的文档
                throw new IllegalStateException("rerank 返回的 index 越界：" + idx
                        + "，本次候选数=" + texts.size());
            }
            items.add(new TextReRankResultItem(idx, score == null ? 0d : score));
        }
        // 不假设服务端已按分数排序：显式排一次，代价可忽略，行为可预期。
        items.sort(Comparator.comparingDouble(TextReRankResultItem::score).reversed());
        return items;
    }

    private record TextReRankResultItem(int index, double score) {
    }

    private static List<Document> head(List<Document> docs, int cap) {
        if (docs == null) {
            return List.of();
        }
        return docs.size() <= cap ? List.copyOf(docs) : List.copyOf(docs.subList(0, cap));
    }

    private static String textOf(Document doc) {
        if (doc.getMetadata() == null) {
            return "";
        }
        ContentBlock block = doc.getMetadata().getContent();
        if (block instanceof TextBlock tb) {
            return tb.getText() == null ? "" : tb.getText();
        }
        return block == null ? "" : String.valueOf(block);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }
}
