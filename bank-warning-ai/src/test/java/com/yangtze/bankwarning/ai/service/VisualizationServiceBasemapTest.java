package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 遥感底图配置下发的回归测试。
 *
 * <p>核心约定：
 * <ol>
 *   <li>auto 且没有 key 时不污染载荷（返回 null）—— 旧载荷消费者完全无感；</li>
 *   <li>key 永不出现在返回的配置里 —— 载荷落盘为 temp_data_*.json（对外可访问目录），
 *       key 只能经子进程环境变量 TIANDITU_TK 传递；</li>
 *   <li>显式关闭（enabled=false）返回 null；强制启用（true）哪怕没有 key 也下发，
 *       由 Python 侧打印未启用原因并继续出图（不会失败）。</li>
 * </ol>
 */
@DisplayName("遥感底图配置下发")
class VisualizationServiceBasemapTest {

    private final VisualizationService service =
            new VisualizationService(null, new ObjectMapper());

    /** @Value 注入的私有字段，测试里用反射赋值（与生产注入等价）。 */
    private void set(String fieldName, Object value) {
        try {
            Field field = VisualizationService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private VisualizationService configured(String enabled, String provider,
                                            String layer, String key, int zoom) {
        set("basemapEnabled", enabled);
        set("basemapProvider", provider);
        set("basemapLayer", layer);
        set("basemapKey", key);
        set("basemapZoom", zoom);
        return service;
    }

    @Test
    @DisplayName("auto 且无 key：不下发（旧载荷完全无感）")
    void autoWithoutKeyReturnsNull() {
        assertNull(configured("auto", "tianditu", "img", "", 0).buildBasemapConfig());
        // 缺省值（字段未注入时的 "auto"）同样不下发
        assertNull(configured(null, null, null, null, 0).buildBasemapConfig());
    }

    @Test
    @DisplayName("auto 且有 key：下发且 key 绝不进配置")
    void autoWithKeySendsConfigWithoutKey() {
        Map<String, Object> config = configured("auto", "tianditu", "img", "MY-SECRET-TK", 0)
                .buildBasemapConfig();

        assertNotNull(config);
        assertEquals("tianditu", config.get("provider"));
        assertEquals("img", config.get("layer"));
        assertFalse(config.containsKey("key"),
                "key 不得写进载荷配置（temp_data_*.json 在对外目录）");
        assertFalse(config.toString().contains("MY-SECRET-TK"),
                "key 值不得以任何形式出现在配置里");
    }

    @Test
    @DisplayName("true 强制启用：无 key 也下发（Python 侧说明原因后继续出图）")
    void forcedEnabledSendsEvenWithoutKey() {
        Map<String, Object> config = configured("true", "tianditu", "img", "", 0)
                .buildBasemapConfig();

        assertNotNull(config);
        assertEquals("tianditu", config.get("provider"));
    }

    @Test
    @DisplayName("显式关闭：返回 null")
    void disabledReturnsNull() {
        assertNull(configured("false", "tianditu", "img", "some-key", 12).buildBasemapConfig());
        assertNull(configured("off", "tianditu", "img", "some-key", 12).buildBasemapConfig());
    }

    @Test
    @DisplayName("provider=local 不需要 key，auto 也直接下发")
    void localProviderNeedsNoKey() {
        Map<String, Object> config = configured("auto", "local", "", "", 0)
                .buildBasemapConfig();

        assertNotNull(config);
        assertEquals("local", config.get("provider"));
    }

    @Test
    @DisplayName("zoom/alpha 缺省不写进配置（Python 侧自适应）")
    void defaultsAreOmitted() {
        Map<String, Object> config = configured("auto", "tianditu", "img", "tk", 0)
                .buildBasemapConfig();

        assertNotNull(config);
        assertFalse(config.containsKey("zoom"), "zoom=0 表示自适应，不应下发");
        assertFalse(config.containsKey("alpha"), "alpha=1.0 是缺省，不应下发");
    }

    @Test
    @DisplayName("显式 zoom 会下发")
    void explicitZoomIsSent() {
        Map<String, Object> config = configured("auto", "tianditu", "img", "tk", 14)
                .buildBasemapConfig();

        assertNotNull(config);
        assertEquals(14, config.get("zoom"));
    }

    @Test
    @DisplayName("provider 缺省回退为 tianditu")
    void providerFallsBackToTianditu() {
        Map<String, Object> config = configured("auto", "  ", "img", "tk", 0)
                .buildBasemapConfig();

        assertNotNull(config);
        assertEquals("tianditu", config.get("provider"));
    }

    @Test
    @DisplayName("配置只含白名单键（新增键必须显式加白名单并同步 Python 侧契约）")
    void onlyWhitelistedKeys() {
        Map<String, Object> config = configured("auto", "tianditu", "cia", "tk", 13)
                .buildBasemapConfig();

        assertNotNull(config);
        // 白名单 = provider / layer / zoom（alpha 未显式配置 (0,1) 开区间值时不下发）
        assertEquals(3, config.size());
        assertTrue(config.containsKey("provider"));
        assertTrue(config.containsKey("layer"));
        assertTrue(config.containsKey("zoom"));
    }
}
