package com.yangtze.bankwarning.ai.config;

/**
 * 阶段 5：当前生效的检索档位名。
 *
 * <p>存在的理由只有一个——<b>让留痕能直接回答「这一行是哪套配置跑出来的」</b>。
 * 阶段 5 之前，同样的 query 在 dense / hybrid / hybrid+rerank 三种档位下结果完全不同，
 * 但 {@code ai_rag_traces} 里看不出区别，事后比较两次检索等于在比两套系统。
 *
 * <p>不引入枚举化的「profile 配置项」：开关仍是唯一真源，本类只做<b>只读派生</b>，
 * 所以不存在「profile 说开了、布尔其实关了」这种两种真源打架的情况。
 */
public final class RagMode {

    public static final String DENSE = "dense";
    public static final String DENSE_RERANK = "dense+rerank";
    public static final String HYBRID = "hybrid";
    public static final String HYBRID_RERANK = "hybrid+rerank";

    private RagMode() {
    }

    public static String name(boolean hybridEnabled, boolean rerankEnabled) {
        if (hybridEnabled) {
            return rerankEnabled ? HYBRID_RERANK : HYBRID;
        }
        return rerankEnabled ? DENSE_RERANK : DENSE;
    }
}
