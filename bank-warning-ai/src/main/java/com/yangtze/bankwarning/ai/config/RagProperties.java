package com.yangtze.bankwarning.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识库检索（RAG）的集中配置 —— 阶段 1 建立，阶段 2 扩充。
 *
 * <p>原来 topK / 阈值散在三个调用点里各写死一份（对话 3、工具 5、REST 5，
 * 阈值都是 0.4），改一次要改三处。这里统一收口，并且每个新行为都带开关，
 * 关掉即可回滚到「该阶段之前」的调用形态，不需要回滚代码。
 *
 * <p>本类不依赖 Spring 容器也能构造：单元测试直接用 {@link #defaults()}。
 */
@Component
public final class RagProperties {

    /** 检索总开关。false = 明确报「知识库已关闭」，绝不静默返回空列表。 */
    public final boolean enabled;

    /**
     * 是否按 user_id 过滤召回结果。
     * 关掉即回到改造前的「检索不过滤」形态，作为出问题时的回滚开关。
     */
    public final boolean permissionFilterEnabled;

    /**
     * 是否把「公共资料」纳入可见集合（阶段 2）。
     * 关掉则退回阶段 1 的「严格只看自己名下 doc_id」口径。
     */
    public final boolean publicKnowledgeEnabled;

    /** 是否写检索留痕表（阶段 2）。关掉后检索行为完全不变，只是不留记录。 */
    public final boolean traceEnabled;

    public final int chatTopK;
    public final int toolTopK;
    public final int searchTopK;
    public final double scoreThreshold;

    /**
     * 权限过滤发生在向量检索之后，因此粗召回要多取几倍，过滤完再截到 topK。
     * 阶段 5 若改成 SQL 内过滤（security trimming），这个系数就可以去掉。
     */
    public final int candidateExpansion;

    public final long retrieveTimeoutMs;
    public final long sseTimeoutMs;

    public RagProperties(
            @Value("${app.ai.rag.enabled:true}") boolean enabled,
            @Value("${app.ai.rag.permission-filter-enabled:true}") boolean permissionFilterEnabled,
            @Value("${app.ai.rag.public-knowledge-enabled:true}") boolean publicKnowledgeEnabled,
            @Value("${app.ai.rag.trace-enabled:true}") boolean traceEnabled,
            @Value("${app.ai.rag.chat-top-k:3}") int chatTopK,
            @Value("${app.ai.rag.tool-top-k:5}") int toolTopK,
            @Value("${app.ai.rag.search-top-k:5}") int searchTopK,
            @Value("${app.ai.rag.score-threshold:0.4}") double scoreThreshold,
            @Value("${app.ai.rag.candidate-expansion:5}") int candidateExpansion,
            @Value("${app.ai.rag.retrieve-timeout-ms:15000}") long retrieveTimeoutMs,
            @Value("${app.ai.rag.sse-timeout-ms:600000}") long sseTimeoutMs) {
        this.enabled = enabled;
        this.permissionFilterEnabled = permissionFilterEnabled;
        this.publicKnowledgeEnabled = publicKnowledgeEnabled;
        this.traceEnabled = traceEnabled;
        this.chatTopK = chatTopK;
        this.toolTopK = toolTopK;
        this.searchTopK = searchTopK;
        this.scoreThreshold = scoreThreshold;
        this.candidateExpansion = candidateExpansion;
        this.retrieveTimeoutMs = retrieveTimeoutMs;
        this.sseTimeoutMs = sseTimeoutMs;
    }

    /** 与配置文件默认值完全一致，供单元测试 / 离线脚本使用。 */
    public static RagProperties defaults() {
        return new RagProperties(true, true, true, true, 3, 5, 5, 0.4, 5, 15_000L, 600_000L);
    }
}
