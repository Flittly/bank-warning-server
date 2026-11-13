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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(golden.cases.stream().anyMatch(c -> "D".equals(c.category)),
                "阶段 5 准备包应登记 D 类关键词/编号题");
        assertTrue(golden.cases.stream().anyMatch(c -> !c.scored),
                "B/C/D 类在灌库与阶段 5 上线前必须只登记不计分，否则 Recall 恒 0 会污染基线");
        assertTrue(golden.cases.stream().filter(c -> "D".equals(c.category))
                        .allMatch(c -> !c.scored),
                "D 类计分会改变 4A 基线的计分题数，破坏 before/after 可比性");
        assertEquals(golden.cases.size(),
                golden.cases.stream().map(c -> c.id).distinct().count());
    }

    @Test
    @DisplayName("阶段 5 前置：把 4A 基线刻度固化成不退化断言，且能抓到真实退化")
    void gatePinsBaselineThresholdsAndDetectsRegression() {
        // 这是「指标计算 + 检索判据」的逻辑门禁，不是线上效果门禁：
        // 固定小库的 1.0 只证明算法与判据没被改坏，线上召回质量看 RagEvalCli 的对照报告。
        RagGoldenSet golden = RagGoldenSet.fromJsonForTest("""
                {"version":"gate","cases":[
                  {"id":"G1","category":"A","question":"g1","expectedDocIds":["d1"],"scored":true},
                  {"id":"G2","category":"A","question":"g2","expectedDocIds":["d2"],"scored":true},
                  {"id":"G3","category":"A","question":"g3","expectedDocIds":[],"mustRefuse":true,"scored":true},
                  {"id":"G4","category":"D","question":"g4","expectedDocIds":["d4"],"scored":false}
                ]}
                """);

        RagRetrievalEvaluator.RetrievalFn healthy = (q, k, caller, tid) -> switch (q) {
            case "g1" -> List.of("d1");
            case "g2" -> List.of("d2");
            case "g3" -> List.of();
            default -> List.of("d4");
        };
        RagEvalReport report = new RagRetrievalEvaluator().run(golden, healthy);

        assertEquals(0, report.errorCount(), "检索失败不得被吞成空列表");
        assertEquals(2, report.countedCases());
        assertEquals(1.0, report.meanRecall(), 1e-9);
        assertEquals(1.0, report.meanPrecision(), 1e-9);
        assertEquals(1.0, report.refusalPassRate(), 1e-9,
                "该拒答的题必须真空返回；4A 真库基线此项为 0.0000，是当前最该修的问题");
        assertEquals(1, report.registeredCount(), "登记题不得混入计分均值");

        // 模拟阶段 5 改坏 doRetrieve()：所有题都返回同一篇（正是 4A 基线 A04/A05 的实际表现）。
        // 门禁必须能抓到，否则「不退化」只是写在注释里。
        RagEvalReport regressed = new RagRetrievalEvaluator().run(golden,
                (q, k, caller, tid) -> List.of("d1"));
        assertEquals(0.0, regressed.refusalPassRate(), 1e-9);
        assertTrue(regressed.meanPrecision() < 1.0);
        assertEquals(0, regressed.errorCount(), "退化是效果变差，不是检索异常，两者要区分");
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
        assertTrue(md.contains("top1分"), "报告必须保留分数列，否则没法观察拒答题的相似度");
        assertTrue(md.contains("T3"));
    }

    @Test
    @DisplayName("拒答口径分层：答案层题不进检索层拒答率，全口径指标仍与 4A 基线可比")
    void refusalLayersAreCountedSeparately() {
        RagGoldenSet golden = RagGoldenSet.fromJsonForTest("""
                {"version":"layer","cases":[
                  {"id":"L1","category":"A","question":"说法不被支持","expectedDocIds":[],"mustRefuse":true,"refusalLayer":"ANSWER","scored":true},
                  {"id":"L2","category":"A","question":"规范不存在","expectedDocIds":[],"mustRefuse":true,"scored":true}
                ]}
                """);
        RagRetrievalEvaluator.RetrievalFn fn = (q, k, caller, tid) ->
                "规范不存在".equals(q) ? List.of() : List.of("BMS.docx");

        RagEvalReport report = new RagRetrievalEvaluator().run(golden, fn);

        assertEquals(1, report.answerLayerRefusalCount());
        assertEquals(1.0, report.retrievalRefusalPassRate(), 1e-9,
                "检索层只该为 L2 负责；L1 是资料存在但说法不被支持，属生成层");
        assertEquals(0.5, report.refusalPassRate(), 1e-9,
                "全口径仍按原定义统计两题，保持与 4A 基线逐字可比");
        assertTrue(report.toMarkdown().contains("检索层拒答通过率"));
    }

    @Test
    @DisplayName("真库路径把 top1 相似度带进报告，不提供分数的数据源显示 —")
    void topScoreIsReportedWhenSourceProvidesIt() {
        RagGoldenSet golden = RagGoldenSet.fromJsonForTest("""
                {"version":"score","cases":[
                  {"id":"S1","category":"A","question":"q","expectedDocIds":["d1"],"scored":true}
                ]}
                """);
        RagRetrievalEvaluator.RetrievalFn scored = new RagRetrievalEvaluator.RetrievalFn() {
            @Override
            public List<String> fetch(String q, int k, String caller, String tid) {
                return List.of("d1");
            }

            @Override
            public List<RagRetrievalEvaluator.RetrievalFn.Scored> fetchScored(
                    String q, int k, String caller, String tid) {
                return List.of(new RagRetrievalEvaluator.RetrievalFn.Scored("d1", 0.8123));
            }
        };

        RagEvalReport report = new RagRetrievalEvaluator().run(golden, scored);
        assertEquals(0.8123, report.results().get(0).topScore(), 1e-9);
        assertTrue(report.toMarkdown().contains("0.8123"));

        RagEvalReport noScore = new RagRetrievalEvaluator().run(golden,
                (q, k, caller, tid) -> List.of("d1"));
        assertNull(noScore.results().get(0).topScore());
        assertTrue(noScore.toMarkdown().contains("—"));
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
