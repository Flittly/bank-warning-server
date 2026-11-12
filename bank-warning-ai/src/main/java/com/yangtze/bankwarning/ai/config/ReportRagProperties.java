package com.yangtze.bankwarning.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 报告轨 RAG 的独立配置 —— 刻意不塞进 {@link RagProperties}。
 *
 * <p>后者是 11 参构造器，被 5 处单元测试直接 {@code new RagProperties(...)}；
 * 给它加参数会让这批测试同时编译失败。报告轨单独一个配置类，两边互不影响。
 *
 * <p><b>默认关闭</b>：库里当前只有 4 段 {@code BMS.docx}（内部算法说明，不是判定标准），
 * 打开会把这些文本当作规范依据注入正式交付物，造成批量污染。等阶段 3 灌入
 * 可从代码验证的平台冻结口径并标 {@code is_public=true} 后再打开。
 */
@Component
public final class ReportRagProperties {

    /** 固定 query 常量：不拼任务动态信息，保证「同任务 + 同语料版本 → 同检索结果」。 */
    public static final String DEFAULT_REPORT_QUERY =
            "崩岸风险等级判定阈值 指标定义 权重 等级划分 风险等级";

    public final boolean enabled;
    public final int topK;
    public final String query;

    public ReportRagProperties(
            @Value("${app.ai.rag.report-enabled:false}") boolean enabled,
            @Value("${app.ai.rag.report-top-k:3}") int topK,
            @Value("${app.ai.rag.report-query:" + DEFAULT_REPORT_QUERY + "}") String query) {
        this.enabled = enabled;
        this.topK = topK > 0 ? topK : 3;
        this.query = (query == null || query.isBlank()) ? DEFAULT_REPORT_QUERY : query.trim();
    }
}
