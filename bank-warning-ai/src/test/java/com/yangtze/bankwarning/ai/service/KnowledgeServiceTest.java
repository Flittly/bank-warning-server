package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.config.UnconfiguredEmbeddingModel;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeUnavailableException.Reason;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import com.yangtze.bankwarning.ai.store.RagTrace;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import reactor.core.publisher.Mono;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 阶段 1 + 阶段 2 回归守卫：检索「要么真跑、要么明说」、按身份（含公共资料）过滤、每次都留痕。
 *
 * <p>全部是纯 POJO + Mockito，不依赖 Spring 容器、数据库或真实 LLM。
 */
@DisplayName("KnowledgeService：不静默失败 + 权限/公共可见性 + 检索留痕")
class KnowledgeServiceTest {

    private EmbeddingModel embeddingModel;
    private VDBStoreBase vectorStore;
    private JdbcTemplate jdbcTemplate;
    private JdbcRagTraceStore traceStore;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        vectorStore = mock(VDBStoreBase.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        traceStore = mock(JdbcRagTraceStore.class);

        when(embeddingModel.getModelName()).thenReturn("text-embedding-v4");
        when(embeddingModel.embed(any())).thenReturn(Mono.just(new double[]{0.1, 0.2}));
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of()));
        when(vectorStore.add(any())).thenReturn(Mono.empty());
    }

    private KnowledgeService service(RagProperties props) {
        return new KnowledgeService(embeddingModel, vectorStore, jdbcTemplate, traceStore, props);
    }

    private static Document doc(String docId, String text) {
        return new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text(text).build())
                .docId(docId)
                .chunkId(docId + "_c0")
                .build());
    }

    @Test
    @DisplayName("密钥未配置：抛 UNCONFIGURED，绝不返回空列表冒充「没查到」")
    void unconfiguredEmbeddingFailsLoudly() {
        EmbeddingModel placeholder = new UnconfiguredEmbeddingModel(1024);
        KnowledgeService svc = new KnowledgeService(placeholder, vectorStore, jdbcTemplate,
                traceStore, RagProperties.defaults());

        KnowledgeUnavailableException ex = assertThrows(KnowledgeUnavailableException.class,
                () -> svc.retrieve("崩岸判别指标", KnowledgeService.Scenario.CHAT, AiCaller.forUser(3L)));

        assertEquals(Reason.UNCONFIGURED, ex.reason());
        assertTrue(ex.getMessage().contains("DASHSCOPE_API_KEY"));
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("没有登录身份：fail-closed 拒绝，且在检索之前就拦住")
    void anonymousCallerRejectedBeforeSearch() {
        KnowledgeUnavailableException ex = assertThrows(KnowledgeUnavailableException.class,
                () -> service(RagProperties.defaults())
                        .retrieve("崩岸", KnowledgeService.Scenario.CHAT, AiCaller.anonymous()));

        assertEquals(Reason.UNAUTHENTICATED, ex.reason());
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("工具参数注入失败（caller=null）等同于匿名，不能放行成看全部")
    void nullCallerIsAnonymous() {
        assertThrows(KnowledgeUnavailableException.class,
                () -> service(RagProperties.defaults())
                        .retrieve("崩岸", KnowledgeService.Scenario.TOOL, null));
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("普通用户：可见集合 SQL 同时覆盖自己名下与公共资料")
    void normalUserSeesOwnAndPublicDocuments() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(
                doc("mine", "我的资料"), doc("public", "公共规范"), doc("others", "别人的资料"))));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(7L)))
                .thenReturn(List.of("mine", "public"));

        List<Document> result = service(RagProperties.defaults())
                .retrieve("崩岸", KnowledgeService.Scenario.CHAT, AiCaller.forUser(7L));

        assertEquals(2, result.size());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(String.class), eq(7L));
        assertTrue(sql.getValue().contains("is_public = TRUE"), "公共资料必须进入可见集合");
        assertTrue(sql.getValue().contains("CAST(? AS BIGINT)"), "可空 userId 必须显式 CAST");
        assertFalse(result.stream().anyMatch(d -> "others".equals(d.getMetadata().getDocId())));
    }

    @Test
    @DisplayName("阶段 2 回滚：public-knowledge-enabled=false 时 SQL 不引用 is_public 列")
    void publicKnowledgeCanBeRolledBack() {
        RagProperties noPublic = new RagProperties(true, true, false, true,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(doc("public", "公共规范"))));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(7L)))
                .thenReturn(List.of());

        service(noPublic).retrieve("崩岸", KnowledgeService.Scenario.CHAT, AiCaller.forUser(7L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(String.class), eq(7L));
        assertFalse(sql.getValue().contains("is_public"), "关开关后必须退回阶段 1 口径");
    }

    @Test
    @DisplayName("管理员：看全部，且不额外查权限表")
    void adminSeesAll() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(
                doc("a", "A"), doc("b", "B"))));

        List<Document> result = service(RagProperties.defaults())
                .retrieve("崩岸", KnowledgeService.Scenario.CHAT, AiCaller.forAdmin());

        assertEquals(2, result.size());
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class), any());
    }

    @Test
    @DisplayName("回滚开关：permission-filter-enabled=false 时回到不过滤形态")
    void permissionFilterCanBeRolledBack() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(
                doc("mine", "我的资料"), doc("others", "别人的资料"))));
        RagProperties noFilter = new RagProperties(true, false, true, true,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);

        List<Document> result = service(noFilter)
                .retrieve("崩岸", KnowledgeService.Scenario.CHAT, AiCaller.forUser(7L));

        assertEquals(2, result.size());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("检索总开关关闭：明确报 DISABLED，不静默")
    void disabledReportsExplicitly() {
        RagProperties off = new RagProperties(false, true, true, true,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);

        KnowledgeUnavailableException ex = assertThrows(KnowledgeUnavailableException.class,
                () -> service(off).retrieve("崩岸", KnowledgeService.Scenario.CHAT, AiCaller.forAdmin()));

        assertEquals(Reason.DISABLED, ex.reason());
    }

    @Test
    @DisplayName("topK 按场景取自配置：对话 3，粗召回放大后再截到 3")
    void scenarioControlsTopK() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(
                doc("a", "A"), doc("b", "B"), doc("c", "C"), doc("d", "D"))));

        List<Document> result = service(RagProperties.defaults())
                .retrieve("崩岸", KnowledgeService.Scenario.CHAT, AiCaller.forAdmin());

        ArgumentCaptor<SearchDocumentDto> captor = ArgumentCaptor.forClass(SearchDocumentDto.class);
        verify(vectorStore).search(captor.capture());
        assertEquals(15, captor.getValue().getLimit(), "3 × candidateExpansion(5) = 15 条粗召回");
        assertEquals(3, result.size(), "过滤/截断后只返回 topK=3");
    }

    @Test
    @DisplayName("确实没命中：正常返回空列表，不抛异常")
    void noHitsIsNotAnError() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of()));

        List<Document> result = service(RagProperties.defaults())
                .retrieve("不存在的术语", KnowledgeService.Scenario.CHAT, AiCaller.forAdmin());

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("向量检索抛异常：转成 SEARCH_FAILED，不再被吞成空列表")
    void searchFailureIsNotSwallowed() {
        when(vectorStore.search(any())).thenThrow(new IllegalStateException("pgvector down"));

        KnowledgeUnavailableException ex = assertThrows(KnowledgeUnavailableException.class,
                () -> service(RagProperties.defaults())
                        .retrieve("崩岸", KnowledgeService.Scenario.CHAT, AiCaller.forAdmin()));

        assertEquals(Reason.SEARCH_FAILED, ex.reason());
    }

    @Test
    @DisplayName("单段嵌入失败：只把成功的段写进库，不再整批硬写")
    void addDocumentsSkipsFailedChunk() {
        when(embeddingModel.embed(any()))
                .thenReturn(Mono.just(new double[]{0.1, 0.2}))
                .thenThrow(new IllegalStateException("embed failed"));

        service(RagProperties.defaults())
                .addDocuments(List.of(doc("a", "A"), doc("b", "B")))
                .block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("a", captor.getValue().get(0).getMetadata().getDocId());
    }

    @Test
    @DisplayName("全部嵌入失败：一次 vectorStore.add 都不调用")
    void allChunksFailedDoesNotWrite() {
        when(embeddingModel.embed(any())).thenThrow(new IllegalStateException("embed failed"));

        service(RagProperties.defaults())
                .addDocuments(List.of(doc("a", "A"), doc("b", "B")))
                .block();

        verify(vectorStore, never()).add(any());
    }

    @Test
    @DisplayName("阶段 2 留痕：成功检索写一行 OK，含命中明细与传入的 trace_id")
    void traceWrittenOnSuccess() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(doc("a", "A"))));
        RagProperties props = RagProperties.defaults();

        service(props).retrieve("崩岸", KnowledgeService.Scenario.CHAT,
                AiCaller.forAdmin(), null, "session-42");

        ArgumentCaptor<RagTrace> captor = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).record(captor.capture());
        RagTrace trace = captor.getValue();
        assertEquals("session-42", trace.traceId());
        assertEquals("CHAT", trace.scenario());
        assertEquals(RagTrace.STATUS_OK, trace.status());
        assertEquals(1, trace.hits());
        assertEquals(1, trace.returned());
        assertEquals("a", trace.hitDetails().get(0).docId());
        assertTrue(trace.hitsJson().contains("\"docId\":\"a\""));
    }

    @Test
    @DisplayName("阶段 2 留痕：明确不可用时也要留一行，带原因而非静默")
    void traceWrittenOnFailure() {
        RagProperties off = new RagProperties(false, true, true, true,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);

        assertThrows(KnowledgeUnavailableException.class,
                () -> service(off).retrieve("崩岸", KnowledgeService.Scenario.TOOL,
                        AiCaller.forAdmin()));

        ArgumentCaptor<RagTrace> captor = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).record(captor.capture());
        assertEquals(Reason.DISABLED.name(), captor.getValue().status());
        assertNotNull(captor.getValue().degradeReason());
    }

    @Test
    @DisplayName("阶段 2 留痕：未传 trace_id 时自动生成，不会为 null")
    void traceIdGeneratedWhenAbsent() {
        service(RagProperties.defaults())
                .retrieve("崩岸", KnowledgeService.Scenario.SEARCH, AiCaller.forAdmin());

        ArgumentCaptor<RagTrace> captor = ArgumentCaptor.forClass(RagTrace.class);
        verify(traceStore).record(captor.capture());
        assertNotNull(captor.getValue().traceId());
        assertFalse(captor.getValue().traceId().isBlank());
    }

    // ---------------- 阶段 5：混合检索 ----------------

    /** 5 参构造器 = 混合检索关闭，这条测试守住「关着就真的没多跑一条 SQL」。 */
    @Test
    @DisplayName("阶段 5 零回归：hybrid 关闭时一条 sparse SQL 都不发")
    void hybridDisabledIssuesNoSparseQuery() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(doc("a", "A"))));

        List<Document> result = service(RagProperties.defaults())
                .retrieve("Zb 的阈值 20 30 40", KnowledgeService.Scenario.SEARCH, AiCaller.forAdmin());

        assertEquals(1, result.size());
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("阶段 5 开启：关键词路命中的段进入最终结果")
    void hybridEnabledFusesSparseHits() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(doc("BMS.docx", "总则"))));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Document> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("body")).thenReturn("Zb 阈值 20 30 40");
                    when(rs.getString("doc_id")).thenReturn("template.json");
                    when(rs.getString("chunk_id")).thenReturn("template.json_chunk_0");
                    return List.of(mapper.mapRow(rs, 0));
                });

        KnowledgeService svc = new KnowledgeService(embeddingModel, vectorStore, jdbcTemplate,
                traceStore, RagProperties.defaults(), HybridRagProperties.enabledWithDefaults());

        List<Document> result = svc.retrieve("Zb 的阈值 20 30 40",
                KnowledgeService.Scenario.SEARCH, AiCaller.forAdmin());

        assertEquals(2, result.size(), "两路各 1 条，融合后应为 2 条");
        assertTrue(result.stream().anyMatch(
                        d -> "template.json".equals(d.getMetadata().getDocId())),
                "关键词路命中的文档必须出现在最终结果里");
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("阶段 5 降级：关键词路抛异常时向量结果照常返回，不打死检索")
    void hybridDegradesWhenSparseFails() {
        when(vectorStore.search(any())).thenReturn(Mono.just(List.of(doc("BMS.docx", "总则"))));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("content 不是合法 JSON"));

        KnowledgeService svc = new KnowledgeService(embeddingModel, vectorStore, jdbcTemplate,
                traceStore, RagProperties.defaults(), HybridRagProperties.enabledWithDefaults());

        List<Document> result = svc.retrieve("Zb 20",
                KnowledgeService.Scenario.SEARCH, AiCaller.forAdmin());

        assertEquals(1, result.size());
        assertEquals("BMS.docx", result.get(0).getMetadata().getDocId());
    }
}
