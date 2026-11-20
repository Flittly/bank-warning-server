package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.config.ModelServiceProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模型结果轮询间隔的守门测试。
 *
 * <p>钉住两件事：① {@code application.yml} 被 .gitignore 排除，新克隆 / 打 jar
 * 时拿不到 yml 里的显式配置，所以代码默认值必须是「调短后」的 200ms，不能偷偷
 * 退回 1000ms；② 配置写错（0 / 负数 / 过大）时必须钳制，否则前者会把
 * {@code /v0/mc/status} 打成高频空转，后者会让白等重新出现。
 */
class ModelGatewayServicePollIntervalTest {

    @Test
    void defaultIsShortEvenWithoutApplicationYml() {
        assertEquals(200L, new ModelServiceProperties().getPollIntervalMs());
    }

    @Test
    void zeroOrNegativeIsClampedToFloor() {
        assertEquals(ModelGatewayService.MIN_POLL_INTERVAL_MS,
                ModelGatewayService.resolvePollIntervalMillis(0L));
        assertEquals(ModelGatewayService.MIN_POLL_INTERVAL_MS,
                ModelGatewayService.resolvePollIntervalMillis(-1L));
        assertEquals(ModelGatewayService.MIN_POLL_INTERVAL_MS,
                ModelGatewayService.resolvePollIntervalMillis(Long.MIN_VALUE));
    }

    @Test
    void oversizedIsClampedToCeiling() {
        assertEquals(ModelGatewayService.MAX_POLL_INTERVAL_MS,
                ModelGatewayService.resolvePollIntervalMillis(60_000L));
        assertEquals(ModelGatewayService.MAX_POLL_INTERVAL_MS,
                ModelGatewayService.resolvePollIntervalMillis(Long.MAX_VALUE));
    }

    @Test
    void valuesInsideRangePassThroughUnchanged() {
        assertEquals(50L, ModelGatewayService.resolvePollIntervalMillis(50L));
        assertEquals(200L, ModelGatewayService.resolvePollIntervalMillis(200L));
        assertEquals(2000L, ModelGatewayService.resolvePollIntervalMillis(2000L));
    }

    @Test
    void setterIsHonouredSoOpsCanOverride() {
        ModelServiceProperties properties = new ModelServiceProperties();
        properties.setPollIntervalMs(150L);
        assertEquals(150L, properties.getPollIntervalMs());
        assertEquals(150L, ModelGatewayService.resolvePollIntervalMillis(properties.getPollIntervalMs()));
    }
}
