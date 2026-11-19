package com.yangtze.bankwarning.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阶段 5：查询改写（归一化 + 领域同义词扩展）的独立配置。
 *
 * <p><b>默认关闭</b>，理由与前面每个阶段一致：它只作用于关键词路，而 OR 扩展
 * 会让召回变宽、不保证变准。开不开由 {@code SparseOnlyEvalCli --rewrite} 的
 * Recall/MRR 对照决定，判据是「Recall@5 提升 ≥2 个百分点且没有任何一题从命中退化为空」。
 *
 * <p><b>为什么不做 LLM 改写</b>：多查询改写每次检索要多一次模型调用（+300~800ms、
 * 按量计费），且同一个问题两次改写可能不同——评测就失去可复现性。同义词表是零延迟、
 * 完全确定的，先用它把「词面对不上」这一层吃掉；不够再谈生成式改写。
 */
@Component
public final class QueryRewriteProperties {

    /** 总开关。关闭时 SparseRetriever 连归一化都不做，行为与阶段 3 逐字一致。 */
    public final boolean enabled;

    /**
     * 单次查询最多追加多少个词。给上限是因为每个追加词都会让 SQL 的 OR 条件变长，
     * 词太多反而把「精确命中」稀释成「什么都命中一点」。
     */
    public final int maxExtraTerms;

    public QueryRewriteProperties(
            @Value("${app.ai.rag.rewrite-enabled:false}") boolean enabled,
            @Value("${app.ai.rag.rewrite-max-extra-terms:6}") int maxExtraTerms) {
        this.enabled = enabled;
        this.maxExtraTerms = maxExtraTerms > 0 ? maxExtraTerms : 6;
    }

    /** 关闭态：供单元测试与离线 CLI 默认路径使用。 */
    public static QueryRewriteProperties disabled() {
        return new QueryRewriteProperties(false, 6);
    }

    /** 打开态（取默认容量），供评测 CLI 的 {@code --rewrite} 使用。 */
    public static QueryRewriteProperties enabledDefaults() {
        return new QueryRewriteProperties(true, 6);
    }
}
