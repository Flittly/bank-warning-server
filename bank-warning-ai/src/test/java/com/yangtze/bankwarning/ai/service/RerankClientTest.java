package com.yangtze.bankwarning.ai.service;

import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.alibaba.dashscope.rerank.TextReRankResult;
import com.yangtze.bankwarning.ai.config.RerankProperties;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 阶段 8：rerank 客户端的确定性单测（SDK 全程 mock，不发网络请求）。
 *
 * <p>守的是四件「错了也不会报错」的事：
 * ① 回填必须按 {@code getIndex()}，不能按返回顺序 zip；② 越界 index 必须拒绝；
 * ③ 不能原地改调用方文档的 score；④ 失败/空文本必须整体降级，不能部分返回。
 */
@DisplayName("阶段 8：rerank 按 index 回填、失败整体降级")
class RerankClientTest {

    private static Document doc(String docId, Double score) {
        Document d = new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text("正文-" + docId).build())
                .docId(docId)
                .chunkId(docId + "_c0")
                .build());
        d.setScore(score);
        return d;
    }

    /**
     * 构造一份「服务端结果」；顺序即返回顺序，故意与输入顺序不同。
     *
     * <p>{@code TextReRankResult} 的构造器是私有的（SDK 只允许
     * {@code fromDashScopeResult} 创建），这里用 mock 承载 {@code getOutput()}，
     * {@code TextReRankOutput} 本身可以 new。
     */
    private static TextReRankResult result(int[] indices, double[] scores) {
        List<TextReRankOutput.Result> items = new ArrayList<>();
        for (int i = 0; i < indices.length; i++) {
            TextReRankOutput.Result r = new TextReRankOutput.Result();
            r.setIndex(indices[i]);
            r.setRelevanceScore(scores[i]);
            items.add(r);
        }
        TextReRankOutput output = new TextReRankOutput();
        output.setResults(items);

        TextReRankResult res = org.mockito.Mockito.mock(TextReRankResult.class);
        org.mockito.Mockito.when(res.getOutput()).thenReturn(output);
        return res;
    }

    @Test
    @DisplayName("返回顺序与输入不同：按 index 回填后重排结果正确")
    void mapsByIndexNotByReturnOrder() throws Exception {
        TextReRank sdk = org.mockito.Mockito.mock(TextReRank.class);
        TextReRankResult res = result(new int[]{2, 0, 1}, new double[]{0.90, 0.50, 0.10});
        org.mockito.Mockito.when(sdk.call(org.mockito.ArgumentMatchers.any())).thenReturn(res);

        RerankClient client = RerankClient.forTest(() -> sdk, RerankProperties.enabledWithDefaults());

        Document a = doc("A", 0.95);
        Document b = doc("B", 0.80);
        Document c = doc("C", 0.20);
        List<Document> out = client.rerank("问题", List.of(a, b, c), 3);

        assertEquals(3, out.size());
        assertEquals("C", out.get(0).getMetadata().getDocId(), "index=2 拿到 0.90 分，应排第一");
        assertEquals("A", out.get(1).getMetadata().getDocId());
        assertEquals("B", out.get(2).getMetadata().getDocId());
        assertEquals(0.90, out.get(0).getScore(), 1e-9, "分数应取 rerank 的相关性分，不是原余弦");
    }

    @Test
    @DisplayName("index 越界：抛异常而不是读到别的文档")
    void rejectsOutOfRangeIndex() throws Exception {
        TextReRank sdk = org.mockito.Mockito.mock(TextReRank.class);
        TextReRankResult res = result(new int[]{0, 5}, new double[]{0.9, 0.8});
        org.mockito.Mockito.when(sdk.call(org.mockito.ArgumentMatchers.any())).thenReturn(res);

        RerankClient client = RerankClient.forTest(() -> sdk, RerankProperties.enabledWithDefaults());
        List<Document> docs = List.of(doc("A", 0.9), doc("B", 0.8));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.rerank("问题", docs, 2));
        assertEquals(true, ex.getMessage().contains("越界"));
    }

    @Test
    @DisplayName("关闭态：原样截断，分数与顺序都不变")
    void disabledKeepsOriginalOrderAndScores() throws Exception {
        RerankClient client = RerankClient.disabled(RerankProperties.disabled());
        List<Document> out = client.rerank("问题",
                List.of(doc("A", 0.91), doc("B", 0.55), doc("C", 0.30)), 2);

        assertEquals(2, out.size());
        assertEquals("A", out.get(0).getMetadata().getDocId());
        assertEquals(0.91, out.get(0).getScore(), 0.0);
    }

    @Test
    @DisplayName("候选含空文本：整体降级为原顺序，不调用 SDK")
    void blankTextDegradesInsteadOfPartialReturn() throws Exception {
        TextReRank sdk = org.mockito.Mockito.mock(TextReRank.class);
        // 若真被调用，返回一条伪造结果；用来断言「没有调用」。
        // 必须先把响应构造好再 stub：result() 内部也要碰 mock，
        // 内联写成 thenReturn(result(...)) 会触发 UnfinishedStubbing。
        TextReRankResult fake = result(new int[]{0}, new double[]{0.99});
        org.mockito.Mockito.when(sdk.call(org.mockito.ArgumentMatchers.any())).thenReturn(fake);

        RerankClient client = RerankClient.forTest(() -> sdk, RerankProperties.enabledWithDefaults());

        Document blank = new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text("  ").build())
                .docId("BLANK").chunkId("BLANK_c0").build());
        blank.setScore(0.88);

        List<Document> out = client.rerank("问题", List.of(doc("A", 0.9), blank), 2);

        assertEquals("A", out.get(0).getMetadata().getDocId(), "降级应保持原顺序");
        assertEquals(0.9, out.get(0).getScore(), 0.0, "降级不得改写分数");
        org.mockito.Mockito.verify(sdk, org.mockito.Mockito.never())
                .call(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("阈值过滤：低于门槛的候选被丢弃，可以少于 limit")
    void thresholdFiltersLowScores() throws Exception {
        TextReRank sdk = org.mockito.Mockito.mock(TextReRank.class);
        TextReRankResult res = result(new int[]{0, 1, 2}, new double[]{0.80, 0.30, 0.05});
        org.mockito.Mockito.when(sdk.call(org.mockito.ArgumentMatchers.any())).thenReturn(res);

        RerankClient client = RerankClient.forTest(() -> sdk, RerankProperties.of(30, 0.2));

        List<Document> out = client.rerank("问题",
                List.of(doc("A", 0.9), doc("B", 0.8), doc("C", 0.7)), 3);

        assertEquals(2, out.size(), "0.05 那条应被门槛滤掉");
        assertEquals("A", out.get(0).getMetadata().getDocId());
        assertEquals("B", out.get(1).getMetadata().getDocId());
    }

    @Test
    @DisplayName("不原地改 score：调用方传入的 Document 保持原分数")
    void doesNotMutateInputDocuments() throws Exception {
        TextReRank sdk = org.mockito.Mockito.mock(TextReRank.class);
        TextReRankResult res = result(new int[]{0, 1}, new double[]{0.7, 0.6});
        org.mockito.Mockito.when(sdk.call(org.mockito.ArgumentMatchers.any())).thenReturn(res);

        RerankClient client = RerankClient.forTest(() -> sdk, RerankProperties.enabledWithDefaults());
        Document a = doc("A", 0.95);
        Document b = doc("B", 0.80);
        client.rerank("问题", List.of(a, b), 2);

        assertEquals(0.95, a.getScore(), 0.0, "入参文档的分数被改写了");
        assertEquals(0.80, b.getScore(), 0.0);
    }
}
