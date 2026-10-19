package com.yangtze.bankwarning.ai.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code query_risk_data} 输出文本的格式契约。
 *
 * <p>“坐标”这一段有两个下游订阅者，格式不能随手改：
 * <ul>
 *   <li>报告工作流按 {@code 坐标：(lng, lat)} 正则把坐标解回来，用于
 *       **按经纬度分区汇总**与 Step 4 的天气查询；</li>
 *   <li>同一条正则还决定断面能否被归入分区。</li>
 * </ul>
 * 改这里的输出格式，必须同步 {@code ReportWorkflowService.COORD_PATTERN}。
 */
@DisplayName("断面风险数据文本格式")
class RiskDataToolsTest {

    private static Map<String, Object> row(Object lng, Object lat) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("start_lng", lng);
        row.put("start_lat", lat);
        return row;
    }

    @Test
    @DisplayName("坐标段固定为 6 位小数，形如 (经度, 纬度)")
    void coordTextFormat() {
        assertEquals("(119.200000, 32.350000)", RiskDataTools.coordText(row(119.2, 32.35)));
        assertEquals("(119.247654, 32.226139)",
                RiskDataTools.coordText(row(119.24765363764432, 32.2261394996327)));
    }

    @Test
    @DisplayName("坐标缺失时输出「未知」而不是 null，断面行仍然保留可解析的等级")
    void coordTextMissing() {
        assertEquals("未知", RiskDataTools.coordText(row(null, null)));
        assertEquals("未知", RiskDataTools.coordText(new LinkedHashMap<>()));
        // 只有一半坐标也算缺失，不能输出半个括号
        assertEquals("未知", RiskDataTools.coordText(row(119.2, null)));
    }

    @Test
    @DisplayName("断面行必须带真 section_id，且用 | 分段")
    void sectionLineCarriesRealSectionId() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section_id", "sec-task-1782124930426-5");
        row.put("section_name", "断面6");
        row.put("bank_name", "长江南京河段");
        row.put("risk_value", 0.31);
        row.put("start_lng", 119.2);
        row.put("start_lat", 32.35);

        String line = RiskDataTools.sectionLine(row, 3);

        assertEquals("  - 断面：断面6（长江南京河段）| ID：sec-task-1782124930426-5"
                        + " | 风险等级：3 | 评估指标：0.31 | 坐标：(119.200000, 32.350000)",
                line);
        // 断面名与 ID 是两套值：Step 5 拿名字查 profile 会一条都查不到
        assertNotEquals(row.get("section_name"), row.get("section_id"));
    }

    @Test
    @DisplayName("section_id 缺失时输出「未知」，不能把 null 字面量写进去")
    void sectionIdTextMissing() {
        assertEquals("未知", RiskDataTools.sectionIdText(new LinkedHashMap<>()));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section_id", "   ");
        assertEquals("未知", RiskDataTools.sectionIdText(row));
        row.put("section_id", "sec-1");
        assertEquals("sec-1", RiskDataTools.sectionIdText(row));
    }
}
