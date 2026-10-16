package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.workflow.PartitionBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「按经纬度分区导出断面详情 CSV」的契约测试。
 *
 * <p>核心命题与报告正文一致：**CSV 的分区 = 报告的分区 = 出图的分区**。
 * 这里盯三件事：
 * <ol>
 *   <li>归区用同一套含边规则（与报告正文共用的 {@link PartitionBounds#contains}），
 *       否则会出现「图上 5 条、CSV 里 4 条」；</li>
 *   <li>N 个分区写 N 个独立文件，坐标缺失/越界的断面另立一个文件而不是被丢掉；</li>
 *   <li>CSV 文本本身（表头、动态指标列、转义、BOM）符合 Excel 可直接打开的要求。</li>
 * </ol>
 */
@DisplayName("分区断面详情 CSV")
class PartitionSectionCsvServiceTest {

    /** 只替换取数一步，其余（分桶、写文件）走真实实现。 */
    private static class StubService extends PartitionSectionCsvService {
        private final List<Map<String, Object>> rows;

        StubService(List<Map<String, Object>> rows) {
            super(null);
            this.rows = rows;
        }

        @Override
        List<Map<String, Object>> querySectionDetails(String taskId) {
            return rows;
        }
    }

    private static Map<String, Object> row(String id, String name, Integer level,
                                           Double lng, Double lat, String indicatorsJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section_id", id);
        row.put("section_name", name);
        row.put("distance", 1234.5);
        row.put("bank_id", 370);
        row.put("bank_name", "长江南京段");
        row.put("risk_level", level);
        row.put("indicators_json", indicatorsJson);
        row.put("start_lng", lng);
        row.put("start_lat", lat);
        row.put("end_lng", lng);
        row.put("end_lat", lat);
        row.put("foot_json", "{\"type\":\"Point\",\"coordinates\":[" + lng + "," + lat + "]}");
        return row;
    }

    private static Map<String, Object> partition(int row, int col, double west, double south,
                                                 double east, double north) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("row", row);
        p.put("col", col);
        p.put("west", west);
        p.put("south", south);
        p.put("east", east);
        p.put("north", north);
        return p;
    }

    private static List<Map<String, Object>> twoPartitions() {
        return List.of(
                partition(0, 0, 119.14, 32.30, 119.40, 32.43),
                partition(0, 1, 119.40, 32.30, 119.66, 32.43));
    }

    // ------------------------------------------------------------------
    // 1. 归区 + 落盘
    // ------------------------------------------------------------------

    @Test
    @DisplayName("N 个分区写 N 个独立 CSV；边界上的断面含边同时进两侧")
    void writesOneCsvPerPartition(@TempDir Path dir) {
        StubService service = new StubService(List.of(
                row("S1", "断面A", 4, 119.20, 32.35, "{\"result\":0.8}"),
                row("S2", "断面B", 3, 119.50, 32.35, "{\"result\":0.6}"),
                row("S3", "断面C", 2, 119.40, 32.35, "{\"result\":0.4}")));

        PartitionSectionCsvService.CsvExport export =
                service.writePartitionCsvs("t1", dir.toFile(), twoPartitions());

        assertEquals(2, export.fileCount());
        assertEquals(2, export.partitions().size());
        assertFalse(export.isEmpty());

        // 分区 1 = A + C（C 正好压在东边界，含边）；分区 2 = B + C
        assertEquals(2, export.partitions().get(0).sectionCount());
        assertEquals(2, export.partitions().get(1).sectionCount());
        assertEquals("分区1_断面详情.csv", export.partitions().get(0).fileName());
        assertEquals("分区2_断面详情.csv", export.partitions().get(1).fileName());
        assertEquals(0, export.unassignedCount());
        assertEquals(null, export.unassignedFile());

        assertTrue(Files.exists(dir.resolve("分区1_断面详情.csv")));
        assertTrue(Files.exists(dir.resolve("分区2_断面详情.csv")));
        assertFalse(Files.exists(dir.resolve(PartitionSectionCsvService.UNASSIGNED_FILE_NAME)));

        // 含边规则落到了文件内容里：分界线上的 S3 必须同时出现在两侧
        String left = read(dir.resolve("分区1_断面详情.csv"));
        String right = read(dir.resolve("分区2_断面详情.csv"));
        assertTrue(left.contains("断面A"), left);
        assertTrue(left.contains("断面C"), "分界线上的断面应含边归入左侧：" + left);
        assertFalse(left.contains("断面B"), "越界断面不应出现：" + left);
        assertTrue(right.contains("断面B"), right);
        assertTrue(right.contains("断面C"), "分界线上的断面应含边归入右侧：" + right);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("坐标缺失的断面不丢：落到「未落入分区」文件，并计入 unassignedCount")
    void keepsUnassignedSections(@TempDir Path dir) {
        StubService service = new StubService(List.of(
                row("S1", "断面A", 4, 119.20, 32.35, "{\"result\":0.8}"),
                row("S9", "断面X", 1, null, null, "{\"result\":0.1}")));

        PartitionSectionCsvService.CsvExport export =
                service.writePartitionCsvs("t1", dir.toFile(), twoPartitions());

        assertEquals(1, export.unassignedCount());
        assertEquals(PartitionSectionCsvService.UNASSIGNED_FILE_NAME, export.unassignedFile());
        // 3 个文件：2 个分区 + 1 个未落入分区
        assertEquals(3, export.fileCount());
        assertTrue(Files.exists(dir.resolve(PartitionSectionCsvService.UNASSIGNED_FILE_NAME)));
    }

    @Test
    @DisplayName("不分幅（无分区）不产出任何 CSV，导出行为与历史一致")
    void noPartitionsMeansNoCsv(@TempDir Path dir) {
        StubService service = new StubService(List.of(
                row("S1", "断面A", 4, 119.20, 32.35, "{\"result\":0.8}")));

        PartitionSectionCsvService.CsvExport export =
                service.writePartitionCsvs("t1", dir.toFile(), List.of());

        assertTrue(export.isEmpty());
        assertEquals(0, export.fileCount());
    }

    // ------------------------------------------------------------------
    // 2. CSV 文本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("表头 = 分区 + 基础列 + 动态指标列；指标并集排序去重")
    void csvHeaderAndIndicatorColumns() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("S1", "断面A", 4, 119.20, 32.35, "{\"result\":0.8,\"slope\":12.5}"));
        rows.add(row("S2", "断面B", 3, 119.50, 32.35, "{\"result\":0.6,\"width\":30}"));

        List<String> keys = PartitionSectionCsvService.collectIndicatorKeys(rows);
        assertEquals(List.of("result", "slope", "width"), keys);

        String csv = PartitionSectionCsvService.toCsv(rows, keys, "第 1 行第 1 列（经度 119.1400~119.4000，纬度 32.3000~32.4300）");
        String header = csv.split("\n")[0];
        assertTrue(header.startsWith("\uFEFF分区,"), "首列应为分区且带 BOM：" + header);
        assertTrue(header.contains("断面ID"), header);
        assertTrue(header.contains("风险等级说明"), header);
        assertTrue(header.endsWith("result,slope,width"), header);

        String first = csv.split("\n")[1];
        assertTrue(first.contains("断面A"), first);
        assertTrue(first.contains("极高风险"), "风险等级说明应中文化：" + first);
        assertTrue(first.contains("119.200000"), "起点经度应 6 位小数：" + first);
        assertTrue(first.endsWith(",0.8,12.5,"), "指标列顺序应与表头一致：" + first);
    }

    @Test
    @DisplayName("含逗号/引号/换行的字段按 RFC 4180 转义")
    void csvEscaping() {
        assertEquals("abc", PartitionSectionCsvService.escape("abc"));
        assertEquals("\"a,b\"", PartitionSectionCsvService.escape("a,b"));
        assertEquals("\"a\"\"b\"", PartitionSectionCsvService.escape("a\"b"));
        assertEquals("\"a\nb\"", PartitionSectionCsvService.escape("a\nb"));
        assertEquals("", PartitionSectionCsvService.escape(null));
    }

    @Test
    @DisplayName("垂足点解析：GeoJSON Point 取 coordinates；非法/缺失返回 NaN")
    void footPointParsing() {
        double[] ok = PartitionSectionCsvService.parseFootPoint(
                "{\"type\":\"Point\",\"coordinates\":[119.2,32.35]}");
        assertEquals(119.2, ok[0], 1e-9);
        assertEquals(32.35, ok[1], 1e-9);

        assertTrue(Double.isNaN(PartitionSectionCsvService.parseFootPoint(null)[0]));
        assertTrue(Double.isNaN(PartitionSectionCsvService.parseFootPoint("not-json")[0]));
    }

    @Test
    @DisplayName("指标 JSON 不是对象时归到 result 列，不丢信息")
    void nonObjectIndicatorsFallBackToResult() {
        Map<String, Object> parsed = PartitionSectionCsvService.parseIndicators("0.42");
        assertEquals(1, parsed.size());
        assertEquals("0.42", parsed.get("result"));
    }

    @Test
    @DisplayName("风险等级说明映射")
    void riskLabels() {
        assertEquals("极高风险", PartitionSectionCsvService.riskLabel(4.0));
        assertEquals("高风险", PartitionSectionCsvService.riskLabel(3.0));
        assertEquals("一般风险", PartitionSectionCsvService.riskLabel(2.0));
        assertEquals("低风险", PartitionSectionCsvService.riskLabel(1.0));
        assertEquals("", PartitionSectionCsvService.riskLabel(null));
    }

    @Test
    @DisplayName("分区标签与报告正文同文案（共用 PartitionBounds）")
    void partitionLabelShared(@TempDir Path dir) {
        StubService service = new StubService(List.of(
                row("S1", "断面A", 4, 119.20, 32.35, "{}")));
        PartitionSectionCsvService.CsvExport export =
                service.writePartitionCsvs("t1", dir.toFile(), twoPartitions());

        String expected = "第 1 行第 1 列（经度 119.1400~119.4000，纬度 32.3000~32.4300）";
        assertEquals(expected, export.partitions().get(0).label());
        // 正文/附录/CSV 三处共用同一实现，避免文案漂移
        assertEquals(expected, PartitionBounds.label(twoPartitions().get(0)));
    }
}
