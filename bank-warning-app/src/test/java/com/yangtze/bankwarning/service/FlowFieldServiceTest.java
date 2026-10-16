package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流场资源的 id 白名单。
 *
 * <p>为什么这组测试值得单独写：id 会被**原样拼进资源路径**再转发给模型服务，
 * 而那个接口（{@code /v0/fs/resource/file?name=...}）是 {@code os.path.join(DIR_RESOURCE, name)}
 * —— 也就是说，只要这里漏掉一个形态，就是一条现成的路径穿越通道。
 * 内置工况与按场景生成两条链路共用同一组 URL，因此更容易出现"改了一边忘了另一边"。
 */
class FlowFieldServiceTest {

    @Test
    void builtinCaseIdUsesHydrodynamicPrefix() {
        assertEquals("hydrodynamic/Mzs/default/standard/",
                FlowFieldService.resourcePrefixFor("84000dc"));
        assertEquals("hydrodynamic/Mzs/default/standard/",
                FlowFieldService.resourcePrefixFor("10000zc"));
        // 流量档按既有契约是 4~6 位（老产物里有 4 位的），别把它当成非法
        assertEquals("hydrodynamic/Mzs/default/standard/",
                FlowFieldService.resourcePrefixFor("8400dc"));
        assertEquals("hydrodynamic/Mzs/default/standard/",
                FlowFieldService.resourcePrefixFor("104000xc"));
    }

    @Test
    void sceneIdUsesFlowFieldPrefix() {
        assertEquals("flowField/", FlowFieldService.resourcePrefixFor("f-fab276146b9c"));
        assertEquals("flowField/", FlowFieldService.resourcePrefixFor("f-d64f861371b9"));
    }

    @Test
    void rejectsPathTraversal() {
        for (String evil : new String[]{
                "../../../etc/passwd",
                "84000dc/../../secret",
                "f-fab276146b9c/../../secret",
                "..",
                "./",
                "hydrodynamic/Mzs/default/standard/84000dc",
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> FlowFieldService.resourcePrefixFor(evil), "应当拒绝：" + evil);
        }
    }

    @Test
    void rejectsMalformedIds() {
        for (String bad : new String[]{
                null,
                "",
                "   ",
                "84000",            // 缺潮型
                "84000dd",          // 潮型不在 dc/xc/zc 内
                "840dc",            // 流量档位数不足（契约是 4~6 位）
                "1234567dc",        // 流量档位数过多
                "84000DC",          // 潮型必须小写
                "f-fab276146b9",    // 场景 id 位数不足
                "f-FAB276146B9C",   // 场景 id 必须小写十六进制
                "f-320f953bc2c2 ",  // 尾随空格
                "xf-fab276146b9c",
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> FlowFieldService.resourcePrefixFor(bad), "应当拒绝：" + String.valueOf(bad));
        }
    }

    @Test
    void sceneIdIsNotAcceptedAsBuiltinCase() {
        // 场景 id 形态与工况 id 完全不同，反过来也一样 —— 两条链路的白名单互不重叠。
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FlowFieldService.resourcePrefixFor("f-0123456789ab/x"));
        assertTrue(error.getMessage().contains("非法"));
    }
}
