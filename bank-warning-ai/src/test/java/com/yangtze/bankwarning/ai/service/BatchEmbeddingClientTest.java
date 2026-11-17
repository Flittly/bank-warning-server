package com.yangtze.bankwarning.ai.service;

import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.ApiException;
import com.google.gson.JsonObject;
import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 阶段 7 批量嵌入的守卫：一次真的装多段、按 {@code textIndex} 落位、条数不符就抛、
 * 只对 429/5xx 重试。
 *
 * <p>全部用 Mockito 挡在 SDK 边界上，不发真实请求、不需要 API Key。
 */
@DisplayName("BatchEmbeddingClient：批量装填 + index 落位 + 重试边界")
class BatchEmbeddingClientTest {

    /** 构造一份响应，第 idx 条的首维就是 idx，方便断言落位。 */
    private static TextEmbeddingResult result(int... indices) {
        List<TextEmbeddingResultItem> items = new ArrayList<>(indices.length);
        for (int idx : indices) {
            TextEmbeddingResultItem item = new TextEmbeddingResultItem();
            item.setTextIndex(idx);
            item.setEmbedding(List.of((double) idx, (double) idx + 0.5));
            items.add(item);
        }
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        output.setEmbeddings(items);
        // TextEmbeddingResult 的构造器是 private，只能经 mock 造一个「带 output 的壳」
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);
        return result;
    }

    private static ApiException apiError(int code) {
        return new ApiException(Status.builder()
                .statusCode(code)
                .code(code == 429 ? "Throttling" : "InvalidParameter")
                .message("stub " + code)
                .build());
    }

    private static List<String> texts(int n) {
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add("第 " + i + " 段正文");
        }
        return list;
    }

    @Test
    @DisplayName("未启用：调用即抛明确原因，不发任何请求")
    void disabledFailsLoudly() {
        BatchEmbeddingClient client = BatchEmbeddingClient.disabled(EmbeddingBatchProperties.disabled());

        assertFalse(client.isEnabled());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.embedBatch(List.of("任意文本")));
        assertTrue(ex.getMessage().contains("batch-embedding-enabled"),
                "报错必须指向具体开关，否则运维只能猜");
    }

    @Test
    @DisplayName("一次请求装 10 条，且按 textIndex 落位（服务端乱序返回也不串位）")
    void embedsTenPerRequestAndPlacesByIndex() throws Exception {
        TextEmbedding sdk = mock(TextEmbedding.class);
        TextEmbeddingResult response = result(9, 3, 7, 0, 5, 1, 8, 2, 6, 4);
        when(sdk.call(any())).thenReturn(response);

        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());

        List<double[]> vectors = client.embedBatch(texts(10));

        ArgumentCaptor<TextEmbeddingParam> captor = ArgumentCaptor.forClass(TextEmbeddingParam.class);
        verify(sdk, times(1)).call(captor.capture());
        JsonObject input = captor.getValue().getInput();
        assertEquals(10, input.getAsJsonArray("texts").size(),
                "一次 HTTP 请求必须装 10 条，这是批量的全部意义");

        assertEquals(10, vectors.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(i, vectors.get(i)[0], 1e-9,
                    "第 " + i + " 段拿到了别的段的向量——按返回顺序 zip 就会这样");
        }
    }

    @Test
    @DisplayName("返回条数少于请求条数：直接抛，不静默丢段")
    void rejectsMismatchedCount() throws Exception {
        TextEmbedding sdk = mock(TextEmbedding.class);
        TextEmbeddingResult response = result(0, 1);
        when(sdk.call(any())).thenReturn(response);

        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.embedBatch(texts(3)));
        assertTrue(ex.getMessage().contains("不符"), "必须说清是条数不符，而不是笼统的失败");
    }

    @Test
    @DisplayName("缺某一段的向量（index 有空位）：抛，不返回半成品")
    void rejectsMissingSlot() throws Exception {
        TextEmbedding sdk = mock(TextEmbedding.class);
        // 请求 3 条，返回 3 条但 index 是 0,1,3 —— 第 2 段没人填，且 3 越界
        TextEmbeddingResult response = result(0, 1, 3);
        when(sdk.call(any())).thenReturn(response);

        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());

        assertThrows(IllegalStateException.class, () -> client.embedBatch(texts(3)));
    }

    @Test
    @DisplayName("400 不重试：参数类错误重试多少次都还是 400")
    void doesNotRetryBadRequest() throws Exception {
        TextEmbedding sdk = mock(TextEmbedding.class);
        when(sdk.call(any())).thenThrow(apiError(400));

        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());

        assertThrows(ApiException.class, () -> client.embedBatch(texts(2)));
        verify(sdk, times(1)).call(any());
    }

    @Test
    @DisplayName("429 重试一次后成功")
    void retriesRateLimitOnce() throws Exception {
        TextEmbedding sdk = mock(TextEmbedding.class);
        TextEmbeddingResult response = result(0, 1);
        when(sdk.call(any())).thenThrow(apiError(429)).thenReturn(response);

        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());

        List<double[]> vectors = client.embedBatch(texts(2));

        assertEquals(2, vectors.size());
        verify(sdk, times(2)).call(any());
    }

    @Test
    @DisplayName("批内混进空文本：预检就抛，不花一次 400 让整批陪葬")
    void rejectsBlankTextBeforeCalling() {
        TextEmbedding sdk = mock(TextEmbedding.class);
        BatchEmbeddingClient client = BatchEmbeddingClient.forTest(() -> sdk,
                EmbeddingBatchProperties.enabledWithDefaults());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.embedBatch(List.of("正常文本", "   ")));
        assertTrue(ex.getMessage().contains("第 1 段"));
        verifyNoInteractions(sdk);
    }
}
