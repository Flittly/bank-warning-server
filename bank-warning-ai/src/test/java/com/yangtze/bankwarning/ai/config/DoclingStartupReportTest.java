package com.yangtze.bankwarning.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docling 启动自检的告警口径。
 *
 * <p>四种部署形态各自该说什么话，钉在这里；日志格式变化不该影响这些判断。
 */
@DisplayName("Docling 自检：离线漏配、路径抄错、首次下载各报一条")
class DoclingStartupReportTest {

    @Test
    @DisplayName("离线且哪都没有模型：必须明确说「第一份 PDF 必然降级」")
    void offlineWithoutModels() {
        List<String> w = DoclingStartupReport.warnings("", false, 0, true);

        assertEquals(1, w.size(), "实际=" + w);
        assertTrue(w.get(0).contains("离线环境且未预置"), "实际=" + w.get(0));
        assertTrue(w.get(0).contains("DOCLING_ARTIFACTS_PATH"), "应给出可执行的下一步");
    }

    @Test
    @DisplayName("有网但没有模型：提示首次会下载，不算故障")
    void onlineWithoutModels() {
        List<String> w = DoclingStartupReport.warnings("", false, 0, false);

        assertEquals(1, w.size(), "实际=" + w);
        assertTrue(w.get(0).contains("联网下载"), "实际=" + w.get(0));
    }

    @Test
    @DisplayName("路径配了但目录不存在：这是抄错了路径，要单独报出来")
    void artifactsPathMissing() {
        List<String> w = DoclingStartupReport.warnings("D:/docling-models", false, 0, false);

        assertEquals(1, w.size(), "实际=" + w);
        assertTrue(w.get(0).contains("不存在"), "实际=" + w.get(0));
        assertTrue(w.get(0).contains("D:/docling-models"));
    }

    @Test
    @DisplayName("路径存在：不报警")
    void artifactsPathReady() {
        assertTrue(DoclingStartupReport.warnings("D:/docling-models", true, 0, true).isEmpty(),
                "有 artifacts 目录时，离线也不该报警");
    }

    @Test
    @DisplayName("默认缓存里已有模型：不报警（离线也成立）")
    void cachedModelsReady() {
        assertTrue(DoclingStartupReport.warnings("", false, 12, true).isEmpty(),
                "缓存里有权重时不该报「未预置」");
    }
}
