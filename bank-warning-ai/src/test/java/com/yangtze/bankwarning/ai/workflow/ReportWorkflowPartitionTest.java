package com.yangtze.bankwarning.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.service.VisualizationService;
import com.yangtze.bankwarning.ai.tool.RiskDataTools;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告「按经纬度分区」的契约测试。
 *
 * <p>核心命题：**报告分区 = 出图分区**。保证方式不是「在 Java 侧复刻一遍网格公式」，
 * 而是报告直接消费出图工具回传的 tiles[].bbox —— 本次出图实际生效的那组数字。
 * 因此这里重点盯两件事：
 * <ol>
 *   <li>产出端（{@link VisualizationTools#formatResult}）与消费端
 *       （{@link ReportWorkflowService#partitionsFrom}）的格式契约不能漂移；</li>
 *   <li>断面归区的判定与 Python 侧 {@code generate_risk_map_tiles} 同一条含边规则，
 *       否则会出现「图上写着 5 条、报告里算出 4 条」。</li>
 * </ol>
 */
@DisplayName("报告经纬度分区")
class ReportWorkflowPartitionTest {

    /**
     * 被测方法都是无状态纯逻辑，构造器只做字段赋值；
     * 用 null 依赖构造即可，避免为了测 3 个纯函数去搭整套 Spring 上下文。
     */
    private final ReportWorkflowService service =
            new ReportWorkflowService(null, null, null, null, null, null, null, null, null, null);

    // ------------------------------------------------------------------
    // 1. 产出/消费契约
    // ------------------------------------------------------------------

    /** 造一份与 Python 分幅返回同形的多幅结果（file_paths + tiles 明细）。 */
    private static Map<String, Object> multiTileResult() {
        String path0 = "C:\\out\\risk_distribution_map_r0c0.png";
        String path1 = "C:\\out\\risk_distribution_map_r0c1.png";

        Map<String, Object> tile0 = new LinkedHashMap<>();
        tile0.put("success", true);
        tile0.put("row", 0);
        tile0.put("col", 0);
        tile0.put("bbox", List.of(119.140, 32.300, 119.400, 32.430));
        tile0.put("sections_in_tile", 5);
        tile0.put("file_path", path0);

        Map<String, Object> tile1 = new LinkedHashMap<>();
        tile1.put("success", true);
        tile1.put("row", 0);
        tile1.put("col", 1);
        tile1.put("bbox", List.of(119.400, 32.300, 119.660, 32.430));
        tile1.put("sections_in_tile", 6);
        tile1.put("file_path", path1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("tile_count", 2);
        result.put("file_path", path0);
        result.put("file_paths", List.of(path0, path1));
        result.put("tiles", List.of(tile0, tile1));
        return result;
    }

    @Test
    @DisplayName("出图工具产出的分区明细，报告端能原样解析（含文件名与 bbox）")
    void partitionContractRoundTrip() {
        String text = VisualizationTools.formatResult(multiTileResult());
        assertTrue(text.contains(ReportWorkflowService.PARTITION_MARKER),
                "分幅出图必须在文本里回传分区明细：" + text);

        List<Map<String, Object>> partitions = service.partitionsFrom(text);
        assertEquals(2, partitions.size());

        Map<String, Object> first = partitions.get(0);
        assertEquals(0, ((Number) first.get("row")).intValue());
        assertEquals(0, ((Number) first.get("col")).intValue());
        assertEquals(119.140, ((Number) first.get("west")).doubleValue(), 1e-6);
        assertEquals(32.430, ((Number) first.get("north")).doubleValue(), 1e-6);
        assertEquals("risk_distribution_map_r0c0.png", first.get("file_name"));

        Map<String, Object> second = partitions.get(1);
        assertEquals(1, ((Number) second.get("col")).intValue());
        assertEquals(119.660, ((Number) second.get("east")).doubleValue(), 1e-6);
        assertEquals("risk_distribution_map_r0c1.png", second.get("file_name"));
    }

    @Test
    @DisplayName("不分幅 / 无标记时解析为空，报告退回原结构（向后兼容）")
    void noMarkerMeansNoPartitions() {
        assertTrue(service.partitionsFrom(null).isEmpty());
        assertTrue(service.partitionsFrom("图表生成成功，文件路径：C:\\out\\a.png").isEmpty());
        assertTrue(service.partitionsFrom(
                VisualizationTools.formatResult(Map.of("success", true, "file_path", "C:\\a.png"))).isEmpty());
    }

    // ------------------------------------------------------------------
    // 2. 归区判定（必须与 Python 同一条含边规则）
    // ------------------------------------------------------------------

    private static Map<String, Object> partition(int row, int col,
                                                 double west, double south,
                                                 double east, double north) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("row", row);
        p.put("col", col);
        p.put("west", west);
        p.put("south", south);
        p.put("east", east);
        p.put("north", north);
        p.put("file_name", "tile_r" + row + "c" + col + ".png");
        return p;
    }

    @Test
    @DisplayName("边界含边：正好落在相邻两幅分界线上的断面同时属于两幅")
    void partitionContainsIsEdgeInclusive() {
        Map<String, Object> left = partition(0, 0, 119.14, 32.30, 119.40, 32.43);
        Map<String, Object> right = partition(0, 1, 119.40, 32.30, 119.66, 32.43);

        assertTrue(ReportWorkflowService.partitionContains(left, 119.40, 32.35), "东边界应含");
        assertTrue(ReportWorkflowService.partitionContains(right, 119.40, 32.35), "西边界应含");
        assertFalse(ReportWorkflowService.partitionContains(left, 119.41, 32.35));
        assertTrue(ReportWorkflowService.partitionContains(left, 119.14, 32.30), "西南角应含");
        assertFalse(ReportWorkflowService.partitionContains(left, 119.13, 32.35));
    }

    @Test
    @DisplayName("分区标签带行列号与经纬范围，正文与附录共用")
    void partitionLabelCarriesBounds() {
        String label = ReportWorkflowService.partitionLabel(partition(1, 0, 119.14, 32.30, 119.66, 32.43));
        assertTrue(label.startsWith("第 2 行第 1 列"), label);
        assertTrue(label.contains("经度 119.1400~119.6600"), label);
        assertTrue(label.contains("纬度 32.3000~32.4300"), label);
    }

    // ------------------------------------------------------------------
    // 3. 断面行解析
    // ------------------------------------------------------------------

    private static final String RISK_TEXT = """
            任务 t1 的风险评估结果（共 4 个断面）：
            高风险：2 个 | 中风险：1 个 | 低风险：1 个

            详细数据：
              - 断面：A（长江南京段）| 风险等级：4 | 评估指标：0.8 | 坐标：(119.200000, 32.350000)
              - 断面：B（长江南京段）| 风险等级：3 | 评估指标：0.6 | 坐标：(119.500000, 32.350000)
              - 断面：C（长江南京段）| 风险等级：2 | 评估指标：0.4 | 坐标：(119.400000, 32.350000)
              - 断面：D（长江南京段）| 风险等级：1 | 评估指标：0.2 | 坐标：未知
            """;

    @Test
    @DisplayName("解析断面行：名称/等级/坐标；坐标缺失不丢弃该行")
    void parseRiskRows() {
        List<ReportWorkflowService.RiskRow> rows = service.parseRiskRows(RISK_TEXT);
        assertEquals(4, rows.size());

        assertEquals("A", rows.get(0).name());
        assertEquals(4, rows.get(0).level());
        assertEquals(119.20, rows.get(0).lng(), 1e-9);
        assertEquals(32.35, rows.get(0).lat(), 1e-9);

        assertEquals("D", rows.get(3).name());
        assertEquals(1, rows.get(3).level());
        // 坐标缺失仍要保住这一行（它还要进高风险清单与断面图），只是归区时落到「未落入」
        assertEquals(null, rows.get(3).lng());
    }

    @Test
    @DisplayName("生产端 sectionLine 与消费端 parseRiskRows 必须对得上：section_id 不能丢")
    void sectionIdRoundTripsThroughText() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section_id", "sec-task-1782124930426-5");
        row.put("section_name", "断面6");
        row.put("bank_name", "长江南京河段");
        row.put("risk_value", 0.31);
        row.put("start_lng", 119.2);
        row.put("start_lat", 32.35);

        List<ReportWorkflowService.RiskRow> rows =
                service.parseRiskRows("详细数据：\n" + RiskDataTools.sectionLine(row, 3));

        assertEquals(1, rows.size());
        assertEquals("断面6", rows.get(0).name());
        assertEquals("sec-task-1782124930426-5", rows.get(0).sectionId(),
                "section_id 解析丢了，Step 5 会拿断面名去查剖面 —— 报告里断面图会整批静默缺失");
        assertEquals(3, rows.get(0).level());
        assertEquals(119.2, rows.get(0).lng(), 1e-9);
    }

    @Test
    @DisplayName("老文本没有 ID 段：sectionId 为 null（由 Step 5 按任务+断面名回查兜底）")
    void legacyTextWithoutSectionIdYieldsNull() {
        assertNull(service.parseRiskRows(RISK_TEXT).get(0).sectionId());
    }

    @Test
    @DisplayName("ID 段为「未知」时按缺失处理，不能当成真 ID 去查库")
    void unknownSectionIdIsTreatedAsMissing() {
        assertNull(ReportWorkflowService.parseSectionId(
                "  - 断面：A（B）| ID：未知 | 风险等级：4 | 坐标：(119.2, 32.3)"));
        assertEquals("sec-1", ReportWorkflowService.parseSectionId(
                "  - 断面：A（B）| ID：sec-1 | 风险等级：4 | 坐标：(119.2, 32.3)"));
    }

    // ------------------------------------------------------------------
    // 4. 分区汇总（喂给 Step 6 的文本）
    // ------------------------------------------------------------------

    private ReportWorkflowService.WorkflowState stateWith(String riskText, String weatherText) {
        ReportWorkflowService.WorkflowState state = new ReportWorkflowService.WorkflowState();
        state.context.put("partitions", List.of(
                partition(0, 0, 119.14, 32.30, 119.40, 32.43),
                partition(0, 1, 119.40, 32.30, 119.66, 32.43)));
        if (riskText != null) {
            state.context.put("riskDataText", riskText);
        }
        if (weatherText != null) {
            state.context.put("weatherResultsText", weatherText);
        }
        return state;
    }

    @Test
    @DisplayName("分区汇总：按分区统计等级分布、高风险清单与对应子图，并单列未落入的断面")
    void buildPartitionSummary() {
        ReportWorkflowService.WorkflowState state = stateWith(RISK_TEXT, "{\"A\": \"晴 25℃\"}");

        String summary = service.buildPartitionSummary(state);

        assertTrue(summary.contains("分区总数：2；断面总数：4"), summary);
        assertEquals(2, countOccurrences(summary, "断面 2 条"), summary);

        // 分区 1：A(4) + C(2 落在分界线上，含边同时归入两侧)
        assertTrue(summary.contains("分区 1（第 1 行第 1 列（经度 119.1400~119.4000，纬度 32.3000~32.4300））"), summary);
        assertTrue(summary.contains("等级分布：极高风险 1、高风险 0、一般风险 1、低风险 0"), summary);
        assertTrue(summary.contains("最高等级：极高风险"), summary);
        assertTrue(summary.contains("高风险断面（等级≥3）：A(4)[晴 25℃]"), summary);
        assertTrue(summary.contains("对应地图：tile_r0c0.png"), summary);

        // 分区 2：B(3) + C(2)
        assertTrue(summary.contains("分区 2（第 1 行第 2 列（经度 119.4000~119.6600，纬度 32.3000~32.4300））"), summary);
        assertTrue(summary.contains("高风险断面（等级≥3）：B(3)"), summary);
        assertTrue(summary.contains("对应地图：tile_r0c1.png"), summary);

        // D 既无坐标也不在任何分区内 → 必须显式列出来，不能悄悄丢
        assertTrue(summary.contains("未落入任何分区的断面 1 条"), summary);
        assertTrue(summary.contains("D"), summary);
    }

    @Test
    @DisplayName("不分幅时汇总为空串 → 报告走原结构，不出现虚构分区")
    void noSplitMeansNoSummary() {
        ReportWorkflowService.WorkflowState state = new ReportWorkflowService.WorkflowState();
        state.context.put("riskDataText", RISK_TEXT);

        assertEquals("", service.buildPartitionSummary(state));
    }

    // ------------------------------------------------------------------
    // 5. 入参校验（起工作流之前 fail-fast）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非法分幅参数在起工作流前就报错，不会白跑 Step 1")
    void invalidSplitRejectedBeforeWorkflow() {
        VisualizationService vizService = new VisualizationService(null, new ObjectMapper());
        ReportWorkflowService workflow =
                new ReportWorkflowService(null, null, null, vizService, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> workflow.executeTaskReport("t1", 0, 2, null));
        assertThrows(IllegalArgumentException.class,
                () -> workflow.executeTaskReport("t1", 2, 2, "not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> workflow.executeTaskReport("t1", 2, 2, "{\"rows\":2}"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }
}
