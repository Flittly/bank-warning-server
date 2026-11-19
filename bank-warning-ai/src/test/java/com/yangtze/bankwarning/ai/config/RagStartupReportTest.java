package com.yangtze.bankwarning.ai.config;

import com.alibaba.dashscope.rerank.TextReRank;
import com.yangtze.bankwarning.ai.service.RerankClient;
import com.yangtze.bankwarning.ai.service.SqlPermissionVectorSearch;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 阶段 5 守卫：启动自检必须能在「配置看起来正常、实际没生效」时发出声音。
 *
 * <p>这些断言针对的是组合而不是日志文本：{@link RagStartupReport#warnings(boolean)}
 * 返回的是「哪些组合该警告」，日志怎么排版不属于被测对象。
 */
@DisplayName("RagStartupReport：生效配置的组合检查")
class RagStartupReportTest {

    private static RerankClient activeRerank(RerankProperties props) {
        // 不联网：forTest 只注入 SDK 工厂，isEnabled() 由 props.enabled 决定。
        return RerankClient.forTest(TextReRank::new, props);
    }

    private static RagStartupReport report(RagProperties props,
                                           HybridRagProperties hybrid,
                                           RerankProperties rerankProps,
                                           RerankClient rerank,
                                           boolean sqlFilter,
                                           boolean traceEnabled,
                                           ChineseKeywordProperties chinese) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // traceEnabled 由 props 决定；store 只用于打印行数，不做断言。
        JdbcRagTraceStore store = new JdbcRagTraceStore(jdbc, props);
        SqlPermissionVectorSearch sql = new SqlPermissionVectorSearch(jdbc, sqlFilter);
        return new RagStartupReport(props, hybrid, rerankProps, new RagThresholdProperties(0.0),
                rerank, sql, chinese, QueryRewriteProperties.disabled(), store, jdbc);
    }

    private static RagProperties props(boolean traceEnabled) {
        return new RagProperties(true, true, true, traceEnabled,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);
    }

    @Test
    @DisplayName("默认档（混合+精排+留痕+SQL 过滤）没有任何警告")
    void defaultSetupHasNoWarnings() {
        RerankProperties rp = RerankProperties.enabledWithDefaults();
        RagStartupReport r = report(props(true), HybridRagProperties.enabledWithDefaults(), rp,
                activeRerank(rp), true, true, ChineseKeywordProperties.enabledDefaults());

        assertEquals(List.of(), r.warnings(true));
    }

    @Test
    @DisplayName("混合检索关闭：必须提醒「与已发布的评测报告不是同一套配置」")
    void hybridOffIsWarned() {
        RerankProperties rp = RerankProperties.enabledWithDefaults();
        RagStartupReport r = report(props(true), HybridRagProperties.disabled(), rp,
                activeRerank(rp), true, true, ChineseKeywordProperties.enabledDefaults());

        assertTrue(joined(r).contains("混合检索关闭"), joined(r));
    }

    @Test
    @DisplayName("缺 search_text 列：关键词路恒空，必须指出要执行哪个迁移")
    void missingSearchTextColumnIsWarned() {
        RerankProperties rp = RerankProperties.enabledWithDefaults();
        RagStartupReport r = report(props(true), HybridRagProperties.enabledWithDefaults(), rp,
                activeRerank(rp), true, true, ChineseKeywordProperties.enabledDefaults());

        assertTrue(joined(r, false).contains("search_text"), joined(r, false));
        assertTrue(joined(r, false).contains("V19"), "警告要能直接指向修复动作：" + joined(r, false));
    }

    @Test
    @DisplayName("rerank 配了开启但客户端不可用：说明真实行为会退回粗排")
    void rerankConfiguredButUnavailableIsWarned() {
        RerankProperties rp = RerankProperties.enabledWithDefaults();
        RagStartupReport r = report(props(true), HybridRagProperties.enabledWithDefaults(), rp,
                RerankClient.disabled(rp), true, true, ChineseKeywordProperties.enabledDefaults());

        assertTrue(joined(r).contains("DASHSCOPE_API_KEY"), joined(r));
    }

    @Test
    @DisplayName("留痕关闭：说明「空返回率与 p95 都无从观察」")
    void traceOffIsWarned() {
        RerankProperties rp = RerankProperties.enabledWithDefaults();
        RagStartupReport r = report(props(false), HybridRagProperties.enabledWithDefaults(), rp,
                activeRerank(rp), true, false, ChineseKeywordProperties.enabledDefaults());

        assertTrue(joined(r).contains("留痕关闭"), joined(r));
    }

    @Test
    @DisplayName("精排池不大于 topK：重排没有余地，必须提醒")
    void rerankPoolTooSmallIsWarned() {
        RerankProperties rp = RerankProperties.of(3, 0.0);
        RagStartupReport r = report(props(true), HybridRagProperties.enabledWithDefaults(), rp,
                activeRerank(rp), true, true, ChineseKeywordProperties.enabledDefaults());

        assertTrue(joined(r).contains("候选池"), joined(r));
    }

    private static String joined(RagStartupReport r) {
        return joined(r, true);
    }

    private static String joined(RagStartupReport r, boolean searchTextExists) {
        return String.join(" | ", r.warnings(searchTextExists));
    }
}
