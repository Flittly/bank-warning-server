package com.yangtze.bankwarning.ai.service;

import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.ApiException;
import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.VDBStoreBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 阶段 7 入库批量化的守卫：分片真的发生、批失败会降级逐条、全失败如实回报、关闭态零变化。
 *
 * <p>用真实的 {@link BatchEmbeddingClient} + 假 SDK，把「线程池 → 分片 → 组装 → 写库」
 * 整条链路都跑到，只有 HTTP 那一层被替换掉。
 */
@DisplayName("KnowledgeService 入库：10 条一批 / 批失败降级 / 失败如实回报")
class KnowledgeServiceBatchIngestTest {

    private EmbeddingModel embeddingModel;
    private VDBStoreBase vectorStore;
    private JdbcTemplate jdbcTemplate;
    private JdbcRagTraceStore traceStore;
    private TextEmbedding sdk;

    /**
     * 预构造「请求 n 条 → 返回 n 条」的响应。
     * 不能在 {@code thenAnswer} 里临时造 —— 那会在 mock 调用期间触发新的 stubbing，
     * Mockito 会直接报 UnfinishedStubbing。
     */
    private final Map<Integer, TextEmbeddingResult> responses = new HashMap<>();

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        vectorStore = mock(VDBStoreBase.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        traceStore = mock(JdbcRagTraceStore.class);
        sdk = mock(TextEmbedding.class);

        when(embeddingModel.getModelName()).thenReturn("text-embedding-v4");
        when(embeddingModel.embed(any())).thenReturn(Mono.just(new double[]{0.1, 0.2}));
        when(vectorStore.add(any())).thenReturn(Mono.empty());

        for (int n = 0; n <= 10; n++) {
            responses.put(n, echoResult(n));
        }
    }

    private static List<Document> docs(int n) {
        List<Document> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String docId = "doc-" + i;
            list.add(new Document(DocumentMetadata.builder()
                    .content(TextBlock.builder().text("第 " + i + " 段正文").build())
                    .docId(docId)
                    .chunkId(docId + "_c0")
                    .build()));
        }
        return list;
    }

    /** 按请求条数返回同样多条的顺序响应。 */
    private static TextEmbeddingResult echoResult(int n) {
        List<TextEmbeddingResultItem> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            TextEmbeddingResultItem item = new TextEmbeddingResultItem();
            item.setTextIndex(i);
            item.setEmbedding(List.of((double) i, 0.5));
            items.add(item);
        }
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        output.setEmbeddings(items);
        // 构造器是 private，用 mock 造一个只带 output 的壳
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);
        return result;
    }

    private static int requestSize(TextEmbeddingParam param) {
        return param.getInput().getAsJsonArray("texts").size();
    }

    private KnowledgeService serviceWith(BatchEmbeddingClient client) {
        return new KnowledgeService(embeddingModel, vectorStore, jdbcTemplate, traceStore,
                RagProperties.defaults(), HybridRagProperties.disabled(),
                SqlPermissionVectorSearch.disabled(), client);
    }

    @Test
    @DisplayName("25 段 → 3 批（10/10/5），HTTP 只发 3 次，写库合并成 1 次")
    void splitsIntoBatchesAndWritesOnce() throws Exception {
        List<Integer> sizes = new ArrayList<>();
        Set<String> threads = new HashSet<>();
        when(sdk.call(any())).thenAnswer(inv -> {
            TextEmbeddingParam param = inv.getArgument(0);
            int n = requestSize(param);
            synchronized (sizes) {
                sizes.add(n);
            }
            synchronized (threads) {
                threads.add(Thread.currentThread().getName());
            }
            return responses.get(n);
        });

        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());
        try {
            List<Document> docs = docs(25);
            KnowledgeService.IngestResult result =
                    serviceWith(client).addDocumentsWithResult(docs).block();

            assertNotNull(result);
            assertEquals(25, result.total());
            assertEquals(25, result.ok());
            assertEquals(0, result.failed());

            verify(sdk, times(3)).call(any());
            sizes.sort(Integer::compareTo);
            assertEquals(List.of(5, 10, 10), sizes, "必须按 10 条一片，最后一片装余数");

            verify(vectorStore, times(1)).add(any());
            for (Document d : docs) {
                assertNotNull(d.getEmbedding(), "每一段都应该拿到向量");
            }
            assertTrue(threads.stream().allMatch(t -> t.startsWith("rag-embed-")),
                    "嵌入必须发生在池线程上，不能压着调用线程串行跑：" + threads);
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("整批 400 → 降级逐条，只丢坏的那条，其余照常入库")
    void fallsBackToPerChunkWhenBatchFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(sdk.call(any())).thenAnswer(inv -> {
            TextEmbeddingParam param = inv.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                // 第一批整批失败：模拟「10 条里有一条超长」
                throw new ApiException(Status.builder()
                        .statusCode(400).code("InvalidParameter").message("text too long").build());
            }
            return responses.get(requestSize(param));
        });

        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());
        try {
            List<Document> docs = docs(10);
            KnowledgeService.IngestResult result =
                    serviceWith(client).addDocumentsWithResult(docs).block();

            assertNotNull(result);
            assertEquals(10, result.ok(), "一条坏文本不该连带另外 9 条一起失败");
            assertEquals(0, result.failed());
            // 1 次失败批 + 10 次逐条重试
            assertEquals(11, calls.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("全部失败：ok=0，不写向量库，且把失败数如实交回（不再伪报 success）")
    void reportsTotalFailureHonestly() throws Exception {
        when(sdk.call(any())).thenThrow(new ApiException(Status.builder()
                .statusCode(401).code("InvalidApiKey").message("bad key").build()));

        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());
        try {
            KnowledgeService.IngestResult result =
                    serviceWith(client).addDocumentsWithResult(docs(4)).block();

            assertNotNull(result);
            assertEquals(4, result.total());
            assertEquals(0, result.ok());
            assertEquals(4, result.failed());
            assertFalse(result.allOk());
            verify(vectorStore, never()).add(any());
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("关闭批量：退回逐段嵌入，一次只送一条，行为与改造前一致")
    void disabledFallsBackToSequentialLoop() {
        BatchEmbeddingClient disabled = BatchEmbeddingClient.disabled(EmbeddingBatchProperties.disabled());

        KnowledgeService.IngestResult result =
                serviceWith(disabled).addDocumentsWithResult(docs(3)).block();

        assertNotNull(result);
        assertEquals(3, result.ok());
        verify(embeddingModel, times(3)).embed(any());
        verifyNoInteractions(sdk);
        verify(vectorStore, times(1)).add(any());
    }
}
