package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.tool.VisualizationTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告 Markdown 的「分区断面详情」附录与文件名时间戳契约。
 *
 * <p>时间戳必须与输出目录同源：附录里的图片与 CSV 都落在
 * {@code report_<taskId>_<ts>/} 下，而导出/删除端点是用报告文件名反解目录名的，
 * 两处时间戳只要差一秒，导出就找不到 CSV、删除也清不掉图片。
 */
@DisplayName("报告 Markdown 附录")
class MarkdownReportServiceTest {

    private final MarkdownReportService service = new MarkdownReportService();

    @Test
    @DisplayName("输出目录名 → 时间戳；不匹配返回 null（调用方退回 now()）")
    void timestampOfTaskDir() {
        assertEquals("20261015_143000",
                MarkdownReportService.timestampOfTaskDir(
                        "C:\\proj\\visualization\\output\\report_t1_20261015_143000"));
        assertEquals("20261015_143000",
                MarkdownReportService.timestampOfTaskDir("report_a_b_20261015_143000"));
        assertNull(MarkdownReportService.timestampOfTaskDir(null));
        assertNull(MarkdownReportService.timestampOfTaskDir(""));
        assertNull(MarkdownReportService.timestampOfTaskDir("C:\\proj\\visualization\\output"));
    }

    @Test
    @DisplayName("分幅报告：附录列出每个分区对应的 CSV 文件与断面数")
    void csvAppendix() {
        PartitionSectionCsvService.CsvExport export = new PartitionSectionCsvService.CsvExport(
                List.of(
                        new PartitionSectionCsvService.PartitionCsv(1,
                                "第 1 行第 1 列（经度 119.1400~119.4000，纬度 32.3000~32.4300）",
                                "分区1_断面详情.csv", 5),
                        new PartitionSectionCsvService.PartitionCsv(2,
                                "第 1 行第 2 列（经度 119.4000~119.6600，纬度 32.3000~32.4300）",
                                "分区2_断面详情.csv", 6)),
                "未落入分区_断面详情.csv", 1);

        String md = service.buildMarkdownContent("t1", "报告正文", List.of(), export);

        assertTrue(md.contains("报告正文"), md);
        assertTrue(md.contains("## 附录：导出数据文件（CSV）"), md);
        assertTrue(md.contains("划分为 2 个分区"), md);
        assertTrue(md.contains("| 分区 1 | 第 1 行第 1 列"), md);
        assertTrue(md.contains("`分区1_断面详情.csv`"), md);
        assertTrue(md.contains("`分区2_断面详情.csv`"), md);
        assertTrue(md.contains("未落入分区"), md);
        assertTrue(md.contains("`未落入分区_断面详情.csv`"), md);
        assertTrue(md.contains("边界含边"), md);
    }

    @Test
    @DisplayName("风险段汇总：不分幅也要出现在附录里，且不谎称「划分为 N 个分区」")
    void riskSummaryAppendixWithoutPartitions() {
        PartitionSectionCsvService.CsvExport export = PartitionSectionCsvService.CsvExport.empty()
                .withRiskSummary(new RiskSegmentSummaryService.Summary("风险段汇总.csv", 12, 302));

        String md = service.buildMarkdownContent("t1", "报告正文", List.of(), export);

        assertTrue(md.contains("`风险段汇总.csv`"), md);
        assertTrue(md.contains("| 全任务 |"), md);
        assertTrue(md.contains("12 段"), md);
        assertTrue(md.contains("不按分区切分"), md);
        assertFalse(md.contains("划分为"), "不分幅时不能说「划分为 N 个分区」: " + md);
    }

    @Test
    @DisplayName("风险段汇总与分区明细并存：附录里两类文件都在（顺序：分区 → 未落入 → 全任务）")
    void riskSummaryCoexistsWithPartitions() {
        PartitionSectionCsvService.CsvExport export = new PartitionSectionCsvService.CsvExport(
                List.of(new PartitionSectionCsvService.PartitionCsv(1, "第 1 行第 1 列",
                        "分区1_断面详情.csv", 5)),
                null, 0)
                .withRiskSummary(new RiskSegmentSummaryService.Summary("风险段汇总.csv", 3, 5));

        String md = service.buildMarkdownContent("t1", "报告正文", List.of(), export);

        int partitionRow = md.indexOf("`分区1_断面详情.csv`");
        int summaryRow = md.indexOf("`风险段汇总.csv`");
        assertTrue(partitionRow > 0 && summaryRow > partitionRow, "汇总应排在分区明细之后: " + md);
        assertTrue(md.contains("划分为 1 个分区"), md);
    }

    @Test
    @DisplayName("不分幅：不出现任何 CSV 附录，结构与历史一致")
    void noAppendixWithoutCsv() {
        String md = service.buildMarkdownContent("t1", "报告正文", List.of(), null);
        assertFalse(md.contains("分区断面详情"), md);

        String md2 = service.buildMarkdownContent("t1", "报告正文", List.of(),
                PartitionSectionCsvService.CsvExport.empty());
        assertFalse(md2.contains("分区断面详情"), md2);
    }

    // ------------------------------------------------------------------
    // 图表路径解析：分幅必须拿到 N 条，且路径不能粘上「分区明细」那一行
    // ------------------------------------------------------------------

    /** 真实的分幅出图返回文本（{@code VisualizationTools.formatResult} 的产出）。 */
    private static String tiledResult(String newlineToken) {
        return "图表生成成功，共 5 幅子图，文件路径："
                + "E:/out/report_t_20261015_151604/risk_distribution_map_r0c0.png, "
                + "E:/out/report_t_20261015_151604/risk_distribution_map_r0c1.png, "
                + "E:/out/report_t_20261015_151604/risk_distribution_map_r0c2.png, "
                + "E:/out/report_t_20261015_151604/risk_distribution_map_r0c3.png, "
                + "E:/out/report_t_20261015_151604/risk_distribution_map_r0c4.png"
                + newlineToken
                + "分区明细(JSON)：[{\"row\":0,\"col\":0,\"west\":118.478200,\"south\":31.700000,"
                + "\"east\":119.133500,\"north\":32.300000,\"sections\":38,"
                + "\"file_name\":\"risk_distribution_map_r0c0.png\"}]";
    }

    @Test
    @DisplayName("分幅（真换行）：5 幅全部取出，路径只到 .png 为止")
    void tiledPathsWithRealNewline() {
        List<String> paths = MarkdownReportService.extractFilePaths(tiledResult("\n"));
        assertEquals(5, paths.size(), paths.toString());
        assertEquals("E:/out/report_t_20261015_151604/risk_distribution_map_r0c0.png", paths.get(0));
        assertEquals("E:/out/report_t_20261015_151604/risk_distribution_map_r0c4.png", paths.get(4));
        paths.forEach(p -> assertFalse(p.contains("分区明细"), p));
    }

    @Test
    @DisplayName("回归：换行是**字面量 \\n**（反斜杠+n）时同样只取到 .png 为止")
    void tiledPathsWithLiteralBackslashN() {
        // 进 trace 日志时换行可能被转义成这两个字符。旧实现按真换行截断，
        // 会把「分区明细(JSON)：…」整行吞进路径，再被 Windows 当成目录分隔符，
        // 最终渲染出 ./risk_distribution_map_r0c4.png/n分区明细(JSON)：[{ 这种坏链接。
        String result = tiledResult("\\n");
        assertTrue(result.contains("png\\n分区明细"), "夹具本身应含字面量 \\n");

        List<String> paths = MarkdownReportService.extractFilePaths(result);
        assertEquals(5, paths.size(), paths.toString());
        paths.forEach(p -> {
            assertFalse(p.contains("分区明细"), p);
            assertFalse(p.contains("\\"), p);
            assertTrue(p.endsWith(".png"), p);
        });
    }

    @Test
    @DisplayName("单图文本：格式与历史一致")
    void singlePath() {
        assertEquals(List.of("E:/out/report_t/a.png"),
                MarkdownReportService.extractFilePaths("图表生成成功，文件路径：E:/out/report_t/a.png"));
        assertEquals("E:/out/report_t/a.png",
                MarkdownReportService.extractFilePath("图表生成成功，文件路径：E:/out/report_t/a.png"));
    }

    @Test
    @DisplayName("结构化 {\"file_path\":\"…\"}：路径尾部粘了含引号的杂字符也只取到 .png")
    void structuredJsonWithTrailingGarbage() {
        // 这是旧实现二次解析时产生的形态：拼字符串导致 JSON 被第一个引号截断
        String broken = "{\"file_path\":\"E:/out/report_t/a.png\\n分区明细(JSON)：[{\"row\":0}]\"}";
        assertEquals(List.of("E:/out/report_t/a.png"),
                MarkdownReportService.extractFilePaths(broken));
    }

    @Test
    @DisplayName("路径里含 \\n 段（如 …\\new_folder\\a.png）不能被误截断")
    void pathContainingBackslashN() {
        // 反斜杠 + n 既可能是「转义后的换行」，也可能只是真实的目录名开头，
        // 因此不能把它当终止符 —— 否则 E:\new_folder\a.png 会被切成 E:
        assertEquals(List.of("E:\\new_folder\\a.png"),
                MarkdownReportService.extractFilePaths("图表生成成功，文件路径：E:\\new_folder\\a.png"));
    }

    @Test
    @DisplayName("取不到路径时返回空列表 / null，不抛异常")
    void noPath() {
        assertTrue(MarkdownReportService.extractFilePaths(null).isEmpty());
        assertTrue(MarkdownReportService.extractFilePaths("").isEmpty());
        assertTrue(MarkdownReportService.extractFilePaths("图表生成失败：超时").isEmpty());
        assertNull(MarkdownReportService.extractFilePath("图表生成失败：超时"));
    }

    @Test
    @DisplayName("生产者→消费者契约：真实 formatResult 的产出必须被解析器完整还原")
    void formatResultContractWithRealProducer() {
        // 用真实的 VisualizationTools.formatResult 造文本，而不是手写夹具：
        // 生产端改了输出格式、消费端没跟上时，这条会直接红。
        String text = VisualizationTools.formatResult(tiledExecResult());
        List<String> expected = List.of(
                "E:/out/report_t_20261015_151604/risk_distribution_map_r0c0.png",
                "E:/out/report_t_20261015_151604/risk_distribution_map_r0c1.png",
                "E:/out/report_t_20261015_151604/risk_distribution_map_r0c2.png",
                "E:/out/report_t_20261015_151604/risk_distribution_map_r0c3.png",
                "E:/out/report_t_20261015_151604/risk_distribution_map_r0c4.png");

        assertEquals(expected, MarkdownReportService.extractFilePaths(text), text);
        assertTrue(text.contains("分区明细(JSON)：["), "生产端应输出分区明细行");

        // trace 把换行转义成字面量 \n 后，解析结果必须完全相同
        assertEquals(expected, MarkdownReportService.extractFilePaths(text.replace("\n", "\\n")));
    }

    /** 5 幅分幅出图的执行结果（形状与 VisualizationService 组装的一致）。 */
    private static Map<String, Object> tiledExecResult() {
        List<String> paths = new ArrayList<>();
        List<Map<String, Object>> tiles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String path = "E:/out/report_t_20261015_151604/risk_distribution_map_r0c" + i + ".png";
            paths.add(path);
            Map<String, Object> tile = new LinkedHashMap<>();
            tile.put("success", true);
            tile.put("row", 0);
            tile.put("col", i);
            tile.put("bbox", List.of(118.4782, 31.7, 119.1335, 32.3));
            tile.put("sections_in_tile", 38);
            tile.put("file_path", path);
            tiles.add(tile);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("tile_count", 5);
        result.put("file_paths", paths);
        result.put("tiles", tiles);
        return result;
    }

    @Test
    @DisplayName("分幅报告的图表附录按分区归组，5 幅各就各位")
    void appendixGroupsTiledCharts() {
        List<Map<String, String>> charts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, String> chart = new LinkedHashMap<>();
            chart.put("tool", "generate_risk_distribution_map");
            chart.put("result", "{\"file_path\":\"E:/out/report_t_20261015_151604/"
                    + "risk_distribution_map_r0c" + i + ".png\"}");
            chart.put("partitionKey", "r0c" + i);
            chart.put("partitionIndex", String.valueOf(i + 1));
            chart.put("partitionLabel", "第 1 行第 " + (i + 1) + " 列");
            charts.add(chart);
        }

        String md = service.buildMarkdownContent("t1", "报告正文", charts);

        for (int i = 1; i <= 5; i++) {
            assertTrue(md.contains("### 分区 " + i + " · 第 1 行第 " + i + " 列"), md);
        }
        assertEquals(5, md.split("#### 风险分布图", -1).length - 1, md);
        assertTrue(md.contains("![风险分布图](./report_t_20261015_151604/risk_distribution_map_r0c4.png)"), md);
        assertFalse(md.contains("分区明细"), md);
    }
}
