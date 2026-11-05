package com.yangtze.bankwarning.ai.config;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import reactor.core.publisher.Mono;

/**
 * 未配置 DashScope API Key 时使用的占位向量模型。
 *
 * <p>存在的唯一原因：{@code DashScopeTextEmbedding.Builder.build()} 会对空 apiKey
 * 直接抛 {@code IllegalStateException("apiKey is required and cannot be null or empty")}，
 * 而 {@code embeddingModel} 是启动期创建的 {@code @Bean}。若不做兜底，
 * 「把 key 清空」这个运维动作会直接导致应用启动失败，而不是仅仅让知识库不可用。
 *
 * <p>因此这里返回一个「构造永远成功、调用永远失败」的实现：
 * <ul>
 *   <li>启动阶段：正常注入到 {@code KnowledgeService}，容器可以起来；</li>
 *   <li>真正调用知识库（embedding / 检索）时：抛出带明确指引的异常，
 *       而不是让用户看到 401 或空结果。</li>
 * </ul>
 *
 * <p>语义上等价于「该能力未开通」，与 qweather 的
 * {@code ${QWEATHER_API_KEY:}} 空默认值保持一致的设计取向。
 */
public class UnconfiguredEmbeddingModel implements EmbeddingModel {

    /** 对外暴露的模型名，用固定标记值便于日志与前端区分「未配置」和「配置错误」。 */
    public static final String MODEL_NAME = "unconfigured";

    private static final String HINT =
            "DashScope API Key 未配置，知识库（向量嵌入/检索）不可用。"
                    + "如需启用，请在部署环境的 .env 中设置 DASHSCOPE_API_KEY 后重建 server 容器。";

    private final int dimensions;

    public UnconfiguredEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public Mono<double[]> embed(ContentBlock content) {
        return Mono.error(new IllegalStateException(HINT));
    }

    @Override
    public String getModelName() {
        return MODEL_NAME;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }
}
