package com.yangtze.bankwarning.ai.config;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 验证「清空 API Key」不会拖垮启动。
 *
 * <p>背景：{@code DashScopeTextEmbedding.Builder.build()} 对空 apiKey 会抛
 * {@code IllegalStateException("apiKey is required and cannot be null or empty")}，
 * 而 {@code embeddingModel} 是启动期创建的 {@code @Bean}。
 * 若没有降级，运维把 key 置空就会让应用整体起不来——而不是只让知识库不可用。
 *
 * <p>这组用例锁定的就是这条边界：空 key 必须「构造成功 + 调用失败」。
 */
class AgentScopeConfigEmbeddingTest {

    private static final int DIMENSIONS = 1024;

    private final AgentScopeConfig config = new AgentScopeConfig();

    @Test
    @DisplayName("空字符串 key → 降级为占位模型，构造不抛异常")
    void blankKeyFallsBackToPlaceholder() {
        EmbeddingModel model = assertDoesNotThrow(
                () -> config.embeddingModel("", "text-embedding-v4", DIMENSIONS));
        assertInstanceOf(UnconfiguredEmbeddingModel.class, model);
        assertEquals(UnconfiguredEmbeddingModel.MODEL_NAME, model.getModelName());
        assertEquals(DIMENSIONS, model.getDimensions());
    }

    @Test
    @DisplayName("纯空白 key 与 null 同样降级（.env 里只留空格也算未配置）")
    void whitespaceAndNullKeysAlsoFallBack() {
        assertInstanceOf(UnconfiguredEmbeddingModel.class,
                config.embeddingModel("   ", "text-embedding-v4", DIMENSIONS));
        assertInstanceOf(UnconfiguredEmbeddingModel.class,
                config.embeddingModel(null, "text-embedding-v4", DIMENSIONS));
    }

    @Test
    @DisplayName("占位模型被真正调用时给出可诊断的错误，而不是返回空向量")
    void placeholderFailsLoudlyOnUse() {
        EmbeddingModel model = config.embeddingModel("", "text-embedding-v4", DIMENSIONS);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> model.embed(TextBlock.builder().text("hi").build()).block());
        // 错误信息必须能让运维一眼知道该配哪个环境变量
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("DASHSCOPE_API_KEY"),
                "错误信息应包含 DASHSCOPE_API_KEY 指引，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("配了 key 时仍然走真实 DashScope 实现")
    void configuredKeyUsesRealModel() {
        EmbeddingModel model = assertDoesNotThrow(
                () -> config.embeddingModel("sk-test-not-a-real-key", "text-embedding-v4", DIMENSIONS));
        assertInstanceOf(DashScopeTextEmbedding.class, model);
        assertEquals(DIMENSIONS, model.getDimensions());
    }
}
