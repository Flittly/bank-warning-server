package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.config.RagThresholdProperties;
import com.yangtze.bankwarning.ai.config.RerankProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 5 守卫：同一份配置下，「用哪把尺子」必须是确定的、可复述的。
 *
 * <p>这几个断言看起来琐碎，但每一条都对应一次真实的口径事故：
 * 把 0.4 用在粗筛上（切掉 0.3953 的真答案）、升级后新参数意外接管老部署、
 * 精排配了开启但客户端不可用却被留痕记成 hybrid+rerank。
 */
@DisplayName("RagThresholdPolicy：三档三把尺")
class RagThresholdPolicyTest {

    private static RagProperties props(double scoreThreshold) {
        return new RagProperties(true, true, true, true,
                3, 5, 5, scoreThreshold, 5, 15_000L, 600_000L);
    }

    @Test
    @DisplayName("纯向量档：0.4 同时是召回与最终门槛（升级后行为不变）")
    void denseKeepsLegacyThreshold() {
        RagThresholdPolicy.Thresholds th = RagThresholdPolicy.of(
                props(0.4), HybridRagProperties.disabled(), false, 0.0,
                new RagThresholdProperties(0.0));

        assertEquals("dense", th.mode());
        assertEquals(0.4, th.recallFloor(), 1e-9);
        assertEquals(0.4, th.finalFloor(), 1e-9);
    }

    @Test
    @DisplayName("纯向量档 + 精排：召回仍是 0.4，不读 recall-floor（老部署没配过这个新参数）")
    void denseWithRerankIgnoresRecallFloor() {
        RagThresholdPolicy.Thresholds th = RagThresholdPolicy.of(
                props(0.4), HybridRagProperties.disabled(), true, 0.15,
                new RagThresholdProperties(0.0));

        assertEquals("dense+rerank", th.mode());
        assertEquals(0.4, th.recallFloor(), 1e-9,
                "混合检索没开时，向量路就是终审，不能被新参数悄悄降到 0");
        assertEquals(0.15, th.finalFloor(), 1e-9);
    }

    @Test
    @DisplayName("混合档：向量路只保召回，最终门槛交给 topK")
    void hybridRelaxesRecallFloor() {
        RagThresholdPolicy.Thresholds th = RagThresholdPolicy.of(
                props(0.4), HybridRagProperties.enabledWithDefaults(), false, 0.0,
                new RagThresholdProperties(0.0));

        assertEquals("hybrid", th.mode());
        assertEquals(0.0, th.recallFloor(), 1e-9);
        assertEquals(0.0, th.finalFloor(), 1e-9, "混合档不设最终门槛，靠 topK 截断");
    }

    @Test
    @DisplayName("混合+精排：最终门槛取精排阈值，向量路底线仍可配")
    void hybridWithRerankUsesRerankThreshold() {
        RagThresholdPolicy.Thresholds th = RagThresholdPolicy.of(
                props(0.4), HybridRagProperties.enabledWithDefaults(), true, 0.18,
                new RagThresholdProperties(0.2));

        assertEquals("hybrid+rerank", th.mode());
        assertEquals(0.2, th.recallFloor(), 1e-9);
        assertEquals(0.18, th.finalFloor(), 1e-9);
        assertTrue(th.reason().contains("精排"), "自检日志要能一句话说清门槛在哪一层：" + th.reason());
    }

    @Test
    @DisplayName("精排配了开启但客户端不可用：档位按实际生效算，不能记成 hybrid+rerank")
    void rerankUnavailableFallsBackToHybridMode() {
        RagThresholdPolicy.Thresholds th = RagThresholdPolicy.of(
                props(0.4), HybridRagProperties.enabledWithDefaults(), false, 0.18,
                new RagThresholdProperties(0.0));

        assertEquals("hybrid", th.mode(), "留痕里的档位必须反映真实行为，否则事后无法归因");
        assertEquals(0.0, th.finalFloor(), 1e-9);
    }

    @Test
    @DisplayName("recall-floor 越界按边界处理，不因一个手误拒绝启动")
    void recallFloorClamped() {
        assertEquals(0.0, new RagThresholdProperties(-1).recallFloor, 1e-9);
        assertEquals(1.0, new RagThresholdProperties(9).recallFloor, 1e-9);
        assertEquals(0.35, new RagThresholdProperties(0.35).recallFloor, 1e-9);
    }
}
