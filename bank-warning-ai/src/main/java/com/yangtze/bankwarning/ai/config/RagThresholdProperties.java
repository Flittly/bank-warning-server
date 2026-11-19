package com.yangtze.bankwarning.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阶段 5：向量路「召回底线」的独立配置。
 *
 * <p><b>为什么不能复用 {@code app.ai.rag.score-threshold}</b>：那个 0.4 是「最终答案合不合格」
 * 的标准，现在被用在了「第一道粗筛」上。混合检索打开后，向量路只是候选提供者——
 * 用最终标准要求候选，等于让初赛按决赛分数线录取：一条 0.3953 的真答案还没进融合
 * 就被淘汰，而后面本来有 RRF 和精排两层可以判它其实相关。
 *
 * <p><b>两种档位、两把尺子</b>：
 * <ul>
 *   <li>纯向量档（hybrid 关闭）：向量路就是最终裁决，沿用 {@code score-threshold}，行为不变；</li>
 *   <li>混合/精排档：向量路只负责「别漏」，用本类的 {@code recall-floor}（默认 0.0），
 *       真正的门槛交给 RRF 之后的精排分。</li>
 * </ul>
 *
 * <p><b>默认 0.0 的依据</b>：只要粗召回条数有上限（{@code MAX_CANDIDATES=100}、精排池 30），
 * 底线放到 0 的代价是「多送几十条给精排」，而不是「多返回几十条给用户」。
 * 但它是可配置的——如果实测发现离题问题被硬塞进 topK，就该往上抬，而不是改代码。
 */
@Component
public final class RagThresholdProperties {

    /**
     * 混合/精排档下，向量路的最低相似度。0 = 不设底线（只受候选条数限制）。
     * 取值落在 [0,1]：余弦相似度的值域，超出范围的配置按边界处理而不是报错——
     * 启动期为一个手误拒绝服务，代价比收益大。
     */
    public final double recallFloor;

    public RagThresholdProperties(@Value("${app.ai.rag.recall-floor:0.0}") double recallFloor) {
        this.recallFloor = Math.max(0.0, Math.min(1.0, recallFloor));
    }

    /**
     * 兼容态：纯向量档下本值不会被使用（策略会改用 {@code score-threshold}）。
     * 供不启动 Spring 容器的单元测试与离线 CLI 使用。
     */
    public static RagThresholdProperties legacy() {
        return new RagThresholdProperties(0.0);
    }
}
