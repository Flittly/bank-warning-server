package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.ai.service.ReportPackageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「AI 出图结果持久化」回归。
 *
 * <p>出图成功后前端会把同一份 markdown 落盘成 {@code viz_<taskId>_<ts>.md}，
 * 这条链路有三个不能退化的点，本测试逐条锁住：
 * <ol>
 *   <li>保存后 {@code kind=viz}、文件名带 {@code viz_} 前缀（列表据此区分出图与报告）；</li>
 *   <li>{@code GET /reports} 能把两类 md 一起读回，并各自解析出 taskId / kind；</li>
 *   <li>删除出图记录时，正文里引用的 {@code viz_} 图片目录一并清掉，不留孤儿 PNG。</li>
 * </ol>
 */
class ReportsControllerVizHistoryTest {

    @TempDir
    Path tmp;

    private ReportsController controller;
    private File outputDir;
    private File reportsDir;

    @BeforeEach
    void setUp() {
        outputDir = tmp.resolve("output").toFile();
        reportsDir = new File(outputDir, "reports");
        assertTrue(reportsDir.mkdirs(), "临时 reports 目录应创建成功");
        controller = new ReportsController(new ReportPackageService());
        ReflectionTestUtils.setField(controller, "outputDir", outputDir.getAbsolutePath());
    }

    @Test
    @DisplayName("保存出图记录：文件名带 viz_ 前缀，列表里 kind=viz 且能解析出 taskId")
    void saveVizRecordAndListIt() {
        Map<String, Object> saved = controller.saveReport(Map.of(
                "taskId", "task-1001",
                "kind", "viz",
                "content", "# AI 出图结果\n\n![a](/v0/bank/ai/viz-output/viz_task-1001_20261021_191111/risk.png)\n"));

        assertEquals(Boolean.TRUE, saved.get("success"));
        String filename = String.valueOf(saved.get("filename"));
        assertTrue(filename.startsWith("viz_task-1001_"), "出图记录前缀应为 viz_：" + filename);
        assertTrue(Files.exists(reportsDir.toPath().resolve(filename)));

        List<Map<String, Object>> list = listItems();
        assertEquals(1, list.size());
        Map<String, Object> item = list.get(0);
        assertEquals("viz", item.get("kind"));
        assertEquals("task-1001", item.get("taskId"));
        assertNotNull(item.get("time"));
    }

    @Test
    @DisplayName("缺省 kind 时仍是普通报告：report_ 前缀 + kind=report")
    void defaultKindStaysReport() {
        Map<String, Object> saved = controller.saveReport(Map.of(
                "taskId", "task-2002", "content", "# 报告\n"));

        String filename = String.valueOf(saved.get("filename"));
        assertTrue(filename.startsWith("report_task-2002_"), filename);
        assertEquals("report", listItems().get(0).get("kind"));
    }

    @Test
    @DisplayName("删除出图记录：连带删除正文引用的 viz_ 图片目录")
    void deleteVizRecordRemovesImageDir() throws Exception {
        String dirName = "viz_task-3003_20261021_191111";
        File imgDir = new File(outputDir, dirName);
        assertTrue(imgDir.mkdirs());
        Path png = imgDir.toPath().resolve("risk_distribution_map.png");
        Files.write(png, new byte[]{1, 2, 3});

        String content = "# AI 出图结果\n\n![a](/v0/bank/ai/viz-output/" + dirName + "/risk_distribution_map.png)\n";
        String filename = String.valueOf(controller.saveReport(Map.of(
                "taskId", "task-3003", "kind", "viz", "content", content)).get("filename"));

        Map<String, Object> result = controller.deleteReport(filename);

        assertEquals(Boolean.TRUE, result.get("success"));
        assertFalse(Files.exists(reportsDir.toPath().resolve(filename)), "md 应被删除");
        assertFalse(imgDir.exists(), "引用的图片目录应被一并删除");
    }

    @Test
    @DisplayName("删除普通报告不会去碰 viz_ 目录（只清自己那一个输出目录）")
    void deletePlainReportLeavesVizDirAlone() throws Exception {
        String dirName = "viz_task-4004_20261021_191111";
        File imgDir = new File(outputDir, dirName);
        assertTrue(imgDir.mkdirs());
        Files.write(imgDir.toPath().resolve("a.png"), new byte[]{1});

        String filename = String.valueOf(controller.saveReport(Map.of(
                "taskId", "task-4004",
                "content", "# 报告\n\n![a](/v0/bank/ai/viz-output/" + dirName + "/a.png)\n")).get("filename"));

        controller.deleteReport(filename);

        assertTrue(imgDir.exists(), "普通报告不该删掉出图目录");
    }

    @Test
    @DisplayName("taskId 带路径分隔符时拒绝保存，宁可失败也不写到 reports 目录外")
    void rejectTraversalTaskId() {
        Map<String, Object> saved = controller.saveReport(Map.of(
                "taskId", "../evil", "kind", "viz", "content", "# x\n"));

        assertEquals(Boolean.FALSE, saved.get("success"));
        assertTrue(String.valueOf(saved.get("error")).contains("路径分隔符"));
    }

    private List<Map<String, Object>> listItems() {
        Object reports = controller.listReports().get("reports");
        assertNotNull(reports);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) reports;
        return items;
    }
}
