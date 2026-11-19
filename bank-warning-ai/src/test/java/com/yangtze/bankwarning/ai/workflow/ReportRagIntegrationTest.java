package com.yangtze.bankwarning.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.config.ReportRagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.KnowledgeUnavailableException;
import com.yangtze.bankwarning.ai.service.KnowledgeUnavailableException.Reason;
import com.yangtze.bankwarning.ai.workflow.ReportWorkflowService.WorkflowState;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告轨 RAG 接入的回归守卫。
 *
 * <p>重点不是「检索准不准」（那是阶段 4 考卷的事），而是：
 * ①开关关闭时提示词逐字不变、不查库；②打开时知识段落与第 9 条确实拼进 sysPrompt；
 * ③知识库挂了要显式降级、不能让整份报告 FAILED，也不能静默当成没资料；
 * ④放进 checkpoint 的必须是纯 JSON 类型，不能是带 {@code double[]} 的 Document。
 */
@DisplayName("报告轨 RAG 接入：灰度开关、提示词契约与失败降级")
class ReportRagIntegrationTest {

    private static final String[] DATA = {
            "TASK-1", "RISK-DATA", "DIST-MAP", "WEATHER", "CHARTS", "PARTITION-BLOCK"};

    @Test
    @DisplayName("开关 false：Step 6 提示词不含任何知识库内容，且 6 段实参不串位")
    void disabledKeepsOriginalPrompt() {
        String prompt = ReportWorkflowService.buildStep6Prompt(
                false, DATA[0], DATA[1], DATA[2], DATA[3], DATA[4], DATA[5], null);

        assertFalse(prompt.contains("规范口径"));
        assertFalse(prompt.contains("9. 「指标分析」"));
        assertTrue(prompt.contains("任务 ID：TASK-1"));
        assertTrue(prompt.contains("=== Step 1：断面风险数据 ===\nRISK-DATA"));
        assertTrue(prompt.contains("=== 按经纬度分区汇总（分区边界与 Step 2 出图完全一致） ===\nPARTITION-BLOCK"));
        assertTrue(prompt.contains("8. **若上面给出了分区汇总**"));
    }

    @Test
    @DisplayName("开关 true：知识段落与第 9 条约束进入提示词，原有 6 段不被挤位")
    void enabledInjectsCriteriaSectionAndRule() {
        String criteria = "[1] 来源 template.json/c0\nZd 阈值 0.30";
        String prompt = ReportWorkflowService.buildStep6Prompt(
                true, DATA[0], DATA[1], DATA[2], DATA[3], DATA[4], DATA[5], criteria);

        assertTrue(prompt.contains("=== 规范口径（知识库，系统自动检索） ==="));
        assertTrue(prompt.contains(criteria), "知识库原文必须原样出现在提示词里");
        assertTrue(prompt.contains("9. 「指标分析」"));
        assertTrue(prompt.contains("只能引用上面「规范口径（知识库）」一节的内容"));
        assertTrue(prompt.contains("其余章节（断面数量、里程、天气、图表、分区边界）仍以 Step 1~5 的数据为准"));
        // 第 7 个 %s 与实参对齐：分区段仍紧邻自己的内容，知识段在其后。
        assertTrue(prompt.indexOf("PARTITION-BLOCK") < prompt.indexOf("规范口径"));
        assertTrue(prompt.indexOf("规范口径（知识库，系统自动检索）") < prompt.indexOf("=== 输出要求 ==="));
        assertTrue(prompt.contains("=== Step 1：断面风险数据 ===\nRISK-DATA"));
    }

    @Test
    @DisplayName("开关 false / 依赖未注入：不查知识库，返回明确说明")
    void disabledNeverCallsKnowledgeService() {
        KnowledgeService svc = mock(KnowledgeService.class);
        WorkflowState state = new WorkflowState();
        state.caller = AiCaller.forAdmin();

        String text = ReportWorkflowService.renderCriteriaContext(state, svc,
                new ReportRagProperties(false, 3, "查询"));
        assertTrue(text.contains("未启用"));
        verify(svc, never()).retrieve(anyString(), any(), any(), any(), anyString());

        String noDeps = ReportWorkflowService.renderCriteriaContext(state, null, null);
        assertTrue(noDeps.contains("未启用"));
    }

    @Test
    @DisplayName("开关 true + 命中：渲染 [n] 序号与来源，快照落 context 且可 JSON 序列化")
    void enabledRendersDocsAndPersistableSnapshot() throws Exception {
        KnowledgeService svc = mock(KnowledgeService.class);
        when(svc.retrieve(anyString(), eq(KnowledgeService.Scenario.REPORT), any(),
                eq(3), anyString()))
                .thenReturn(List.of(doc("template.json", "Zd 阈值 0.30")));
        WorkflowState state = new WorkflowState();
        state.caller = AiCaller.forAdmin();
        state.context.put("runId", "run-42");

        String text = ReportWorkflowService.renderCriteriaContext(state, svc,
                new ReportRagProperties(true, 3, "崩岸风险等级判定阈值"));

        assertTrue(text.contains("[1] 来源：template.json"), text);
        assertTrue(text.contains("Zd 阈值 0.30"));
        assertEquals("report-run-42-step6", state.context.get("criteriaTraceId"));
        assertNotNull(state.context.get("criteriaSnapshot"));
        // checkpoint 的硬约束：纯 Map/List，不能出现 Document 的 double[] embedding。
        String json = new ObjectMapper().writeValueAsString(state.context);
        assertFalse(json.contains("embedding"));
        assertTrue(json.contains("template.json"));
        assertFalse(state.context.get("criteriaSnapshot") instanceof Document);
        assertTrue(state.context.get("criteriaSnapshot") instanceof List<?>);
    }

    @Test
    @DisplayName("命中带 payload：来源显示「第 k/n 段」，内部 chunk_id 不出现在提示词里")
    void sourceLabelShowsChunkPosition() {
        KnowledgeService svc = mock(KnowledgeService.class);
        Document d = new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text("崩岸等级判定阈值").build())
                .docId("template.json")
                .chunkId("template.json_chunk_4")
                .addPayload("chunkIndex", "4")
                .addPayload("totalChunks", "7")
                .build());
        when(svc.retrieve(anyString(), eq(KnowledgeService.Scenario.REPORT), any(),
                eq(3), anyString())).thenReturn(List.of(d));
        WorkflowState state = new WorkflowState();
        state.caller = AiCaller.forAdmin();

        String text = ReportWorkflowService.renderCriteriaContext(state, svc,
                new ReportRagProperties(true, 3, "崩岸风险等级判定阈值"));

        assertTrue(text.contains("[1] 来源：template.json · 第 5/7 段"), text);
        assertFalse(text.contains("_chunk_4"),
                "内部 chunk_id 对读者无意义，不该出现在给模型/报告的正文里");
    }

    @Test
    @DisplayName("知识库不可用：只降级为一段受限说明，不抛异常（否则整份报告 FAILED）")
    void unavailableDegradesWithoutThrowing() {
        KnowledgeService svc = mock(KnowledgeService.class);
        when(svc.retrieve(anyString(), eq(KnowledgeService.Scenario.REPORT), any(),
                eq(3), anyString()))
                .thenThrow(new KnowledgeUnavailableException(Reason.UNCONFIGURED,
                        "DashScope API Key 未配置"));
        WorkflowState state = new WorkflowState();
        state.caller = AiCaller.forAdmin();

        String text = ReportWorkflowService.renderCriteriaContext(state, svc,
                new ReportRagProperties(true, 3, "查询"));

        assertTrue(text.contains("知识库不可用"));
        assertTrue(text.contains("UNCONFIGURED"));
        assertTrue(text.contains("不得编造判定依据"));
        assertFalse(text.contains("以下内容来自平台知识库"));
    }

    @Test
    @DisplayName("空结果：不得编造口径，明确写「知识库未提供」")
    void emptyResultRefusesToInvent() {
        KnowledgeService svc = mock(KnowledgeService.class);
        when(svc.retrieve(anyString(), eq(KnowledgeService.Scenario.REPORT), any(),
                eq(3), anyString())).thenReturn(List.of());
        WorkflowState state = new WorkflowState();
        state.caller = AiCaller.forAdmin();

        String text = ReportWorkflowService.renderCriteriaContext(state, svc,
                new ReportRagProperties(true, 3, "查询"));
        assertTrue(text.contains("知识库未提供判定口径"));
        assertTrue(text.contains("不得编造"));
    }

    @Test
    @DisplayName("固定 query 不拼任务动态信息，且默认关闭")
    void reportQueryIsFixedAndDefaultOff() {
        ReportRagProperties defaults = new ReportRagProperties(false, 3, null);
        assertFalse(defaults.enabled);
        assertEquals(ReportRagProperties.DEFAULT_REPORT_QUERY, defaults.query);
        assertEquals(ReportRagProperties.DEFAULT_REPORT_QUERY,
                new ReportRagProperties(true, 3, "  ").query);
        assertEquals(3, new ReportRagProperties(true, 0, null).topK);
        assertEquals("REPORT", KnowledgeService.Scenario.REPORT.name());
    }

    private static Document doc(String docId, String text) {
        return new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text(text).build())
                .docId(docId)
                .chunkId(docId + "_c0")
                .build());
    }
}
