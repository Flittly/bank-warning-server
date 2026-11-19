package com.yangtze.bankwarning.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阶段 3：中文关键词路（jieba 分词 + tsvector）的独立配置。
 *
 * <p><b>默认开启，但不改变线上行为</b>：中文词项只在混合检索（{@code hybrid-enabled}）
 * 打开时才被 {@link com.yangtze.bankwarning.ai.service.SparseRetriever} 使用，
 * 而那个开关默认仍是 false。所以「中文分词就绪」与「关键词路上线」依旧是两件事。
 *
 * <p><b>保留关闭开关的意义</b>：分词质量出问题（如某个领域词被切碎）时，
 * 关掉它即可退回「只认 ASCII 锚点」的阶段 5 行为，不必回滚代码。
 * 关掉后 {@code search_text} 列仍在、索引仍在，只是不再被查询。
 */
@Component
public final class ChineseKeywordProperties {

    /** 关键词路是否使用中文词项。false = 退回阶段 5 的纯锚点行为。 */
    public final boolean enabled;

    /**
     * 单次查询最多用多少个词项。上限的意义是限制 tsquery 长度与扫描面：
     * 长问句切出二三十个词，全塞进去既慢又容易把无关段落捞进来。
     *
     * <p>12 是「一句话通常 3~6 个实词 + 几个限定词」的经验上界，不是调参结果；
     * 语料规模化后按离线评测再定，不要凭感觉改。
     */
    public final int maxTerms;

    public ChineseKeywordProperties(
            @Value("${app.ai.rag.chinese-keyword-enabled:true}") boolean enabled,
            @Value("${app.ai.rag.chinese-keyword-max-terms:12}") int maxTerms) {
        this.enabled = enabled;
        this.maxTerms = maxTerms > 0 ? maxTerms : 12;
    }

    /** 不启动 Spring 容器时的默认实例（单元测试、离线评测 CLI 用）。 */
    public static ChineseKeywordProperties enabledDefaults() {
        return new ChineseKeywordProperties(true, 12);
    }

    /** 关闭态实例：用于与阶段 5 行为做 A/B 对照。 */
    public static ChineseKeywordProperties disabled() {
        return new ChineseKeywordProperties(false, 12);
    }
}
