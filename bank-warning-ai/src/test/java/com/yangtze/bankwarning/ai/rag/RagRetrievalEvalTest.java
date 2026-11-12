package com.yangtze.bankwarning.ai.rag;

import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.config.UnconfiguredEmbeddingModel;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.VDBStoreBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 * 阶段 4A 的 CI 门禁：固定小向量库 + 确定性断言，零网络、零 LLM、零 Spring 容器。
 *
 * <p>这里断言的是「检索逻辑不回归」，不是「召回质量达标」：后者要连真库、要 key、
 * 结果非确定，由 {@link RagEvalCli} 手动跑并出对照报告。两者混在一起会让
 * {@code mvn test} 变慢且偶发变红。
 */
@DisplayName("RAG 阶段 4A：考卷有效性与检索层回归门禁")
class RagRetrievalEvalTest {

    @Test
    @DisplayName("考卷可加载、标注完整、id 不重复，且保留了只登记不计分的题")
    void goldenSetIsWellFormed() throws Exception {
        RagGoldenSet golden = RagGoldenSet.load();
        golden.validate();

        assertFalse(golden.cases.isEmpty());
        assertTrue(golden.cases.stream().anyMatch(c -> "A".equals(c.category)));
        assertTrue(golden.cases.stream().anyMatch(c -> "B".equals(c.category)));
        assertTrue(golden.cases.stream().anyMatch(c -> "C".equals(c.category)));
        assertTrue(golden.cases.stream().anyMatch(c -> !c.scored),
                "B/C 类在灌库前必须只登记不计分，否则 Recall 恒 0 会污染基线");
        assertEquals(golden.cases.size(),
                golden.cases.stream().map(c -> c.id).distinct().count());
    }

    @Test
    @DisplayName("指标计算正确：期望块全召回 recall=1，拒答题返回空记通过，异常记 ERROR 不静默")
    void evaluatorComputesMetricsAndKeepsFailuresVisible() {
        RagGoldenSet golden = RagGoldenSet.fromJsonForTest("""
                {"version":"t","cases":[
                  {"id":"T1","category":"A","question":"q1","expectedDocIds":["d1","d2"],"scored":true},
                  {"id":"T2","category":"A","question":"q2","expectedDocIds":[],"mustRefuse":true,"scored":true},
                  {"id":"T3","category":"A","question":"q3","expectedDocIds":["d3"],"scored":true},
                  {"id":"T4","category":"B","question":"q4","expectedDocIds":["never"],"scored":false}
                ]}
                """);

        RagRetrievalEvaluator.RetrievalFn fn = (q, k, caller, tid) -> switch (q) {
            case "q1" -> List.of("d1", "d2", "noise");
            case "q2" -> List.of();
            case "q3" -> throw new IllegalStateException("向量库连不上");
            default -> List.of();
        };

        RagEvalReport report = new RagRetrievalEvaluator().run(golden, fn);
        var byId = report.results().stream().collect(java.util.stream.Collectors.toMap(
                RagEvalReport.CaseResult::id, r -> r));

        assertEquals("OK", byId.get("T1").status());
        assertEquals(1.0, byId.get("T1").recall(), 1e-9);
        assertEquals(2.0 / 3.0, byId.get("T1").precision(), 1e-9);
        assertTrue(byId.get("T2").mustRefuseOk());
        assertEquals("ERROR", byId.get("T3").status());
        assertTrue(byId.get("T3").error().contains("向量库连不上"));
        assertEquals(1, report.errorCount());
        assertEquals(1, report.registeredCount());
        assertEquals(1.0, report.meanRecall(), 1e-9, "异常题与登记题不得拉低计分均值");

        String md = report.toMarkdown();
        assertTrue(md.contains("平均 Recall@k"));
        assertTrue(md.contains("不是领域认证"));
        assertTrue(md.contains("T3"));
    }

    @Test
    @DisplayName("复用唯一入口 retrieve()：普通用户看不到他人私有资料，公共资料仍可见")
    void gateReusesRealRetrievalEntryAndHonoursPermissionFilter() throws Exception {
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        VDBStoreBase store = mock(VDBStoreBase.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcRagTraceStore trace = mock(JdbcRagTraceStore.class);

        when(embedding.getModelName()).thenReturn("text-embedding-v4");
        when(embedding.embed(any())).thenReturn(Mono.just(new double[]{0.1, 0.2}));
        when(store.search(any())).thenReturn(Mono.just(List.of(
                doc("BMS.docx"), doc("others-private"))));
        // 普通用户(7L)可见集合只含公共的 BMS.docx
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L)))
                .thenReturn(List.of("BMS.docx"));

        KnowledgeService svc = new KnowledgeService(embedding, store, jdbc, trace,
                RagProperties.defaults());
        RagRetrievalEvaluator.RetrievalFn fn = (q, k, caller, tid) -> {
            AiCaller who = "admin".equals(caller) ? AiCaller.forAdmin() : AiCaller.forUser(7L);
            return svc.retrieve(q, KnowledgeService.Scenario.SEARCH, who, k, tid)
                    .stream().map(d -> String.valueOf(d.getMetadata().getDocId())).toList();
        };

        RagGoldenSet golden = RagGoldenSet.fromJsonForTest("""
                {"version":"t","cases":[
                  {"id":"U1","category":"A","question":"查公共资料","expectedDocIds":["BMS.docx"],"caller":"user","scored":true},
                  {"id":"U2","category":"A","question":"查公共资料","expectedDocIds":["BMS.docx"],"caller":"admin","scored":true}
                ]}
                """);
        RagEvalReport report = new RagRetrievalEvaluator().run(golden, fn);

        assertEquals(0, report.errorCount());
        var byId = report.results().stream().collect(
                java.util.stream.Collectors.toMap(RagEvalReport.CaseResult::id, r -> r));
        assertFalse(byId.get("U1").returned().contains("others-private"),
                "普通用户不得看到他人私有 doc_id");
        assertTrue(byId.get("U2").returned().contains("others-private"),
                "管理员 seeAll() 不过滤");
    }

    @Test
    @DisplayName("失败不静默：key 未配置时留 ERROR 状态，绝不退化成空列表")
    void unconfiguredKeyIsRecordedAsError() {
        VDBStoreBase store = mock(VDBStoreBase.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcRagTraceStore trace = mock(JdbcRagTraceStore.class);
        when(store.search(any())).thenReturn(Mono.just(List.of()));

        KnowledgeService svc = new KnowledgeService(new UnconfiguredEmbeddingModel(1024),
                store, jdbc, trace, RagProperties.defaults());
        RagRetrievalEvaluator.RetrievalFn fn = (q, k, caller, tid) ->
                svc.retrieve(q, KnowledgeService.Scenario.SEARCH, AiCaller.forAdmin(), k, tid)
                        .stream().map(d -> String.valueOf(d.getMetadata().getDocId())).toList();

        RagGoldenSet golden = RagGoldenSet.fromJsonForTest("""
                {"version":"t","cases":[
                  {"id":"E1","category":"A","question":"q","expectedDocIds":["x"],"scored":true}
                ]}
                """);
        RagEvalReport report = new RagRetrievalEvaluator().run(golden, fn);

        assertEquals(1, report.errorCount());
        assertEquals("ERROR", report.results().get(0).status());
        assertTrue(report.results().get(0).error().contains("DASHSCOPE_API_KEY"));
        verify(store, never()).search(any());
    }

    @Test
    @DisplayName("离线 runner 的 self-check 能产出报告文件，且文件名不带 Test 后缀不会被 Surefire 收集")
    void cliSelfCheckWritesReport() throws Exception {
        Path dir = Files.createTempDirectory("rag-eval-test");
        Path out = dir.resolve("rag-eval.md");
        int code = RagEvalCli.execute(new String[]{"--self-check", "--out", out.toString()});
        assertEquals(0, code);
        assertTrue(Files.exists(out));
        String md = Files.readString(out);
        assertNotNull(md);
        assertTrue(md.contains("## 汇总"));
        assertTrue(md.contains("A01"));
    }

    private static Document doc(String docId) {
        return new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text("fixture").build())
                .docId(docId)
                .chunkId(docId + "_c0")
                .build());
    }
}
