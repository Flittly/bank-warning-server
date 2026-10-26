package com.yangtze.bankwarning.ai.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RiskDataTools {

    private static final Logger log = LoggerFactory.getLogger(RiskDataTools.class);
    private final JdbcTemplate jdbcTemplate;

    public RiskDataTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(name = "query_risk_data", description = "查询任务的所有断面风险评估数据，包括断面信息、风险等级、指标数据和所属银行")
    public String queryRiskData(@ToolParam(name = "task_id", description = "任务ID") String taskId) {
        log.info("[tool] querying risk data, taskId={}", taskId);

        // 坐标必须一并查回：报告要按经纬度分区汇总（与出图同一套分区），
        // 且 Step 4 的天气查询需要 (lng, lat)——此前不带坐标，天气只能拿到 null。
        // 口径与 VisualizationService.queryRiskData 完全一致：
        // 断面端点在 cross_sections.geom 上（banks.geom 不被写入，不要用它）。
        String sql = "SELECT r.section_id, cs.section_name, r.risk_level, " +
                "r.indicators->>'result' as risk_value, b.bank_name, " +
                "ST_X(ST_StartPoint(cs.geom)) as start_lng, " +
                "ST_Y(ST_StartPoint(cs.geom)) as start_lat, " +
                "ST_X(ST_EndPoint(cs.geom)) as end_lng, " +
                "ST_Y(ST_EndPoint(cs.geom)) as end_lat " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON COALESCE(cs.bank_id, r.bank_id) = b.bank_id " +
                "WHERE r.task_id = ? AND r.deleted_at IS NULL " +
                // 人工标记无效的断面不进报告取数：与前端图面、风险段汇总同一口径，
                // 否则「我明明藏起来的断面」会重新出现在报告文字里。
                "AND COALESCE(cs.manual_invalid, false) = false " +
                "ORDER BY r.risk_level DESC";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, taskId);

        if (rows.isEmpty()) {
            return "未查询到任务ID为 " + taskId + " 的风险评估数据。";
        }

        int[] counts = new int[3]; // high, medium, low
        String details = rows.stream().map(row -> {
            Object rl = row.get("risk_level");
            int level;
            if (rl instanceof Number n) { level = n.intValue(); }
            else if (rl instanceof String s) { try { level = Integer.parseInt(s); } catch (NumberFormatException e) { level = 0; } }
            else { level = 0; }
            if (level >= 4) counts[0]++;
            else if (level >= 2) counts[1]++;
            else counts[2]++;

            return sectionLine(row, level);
        }).collect(Collectors.joining("\n"));

        return String.format(
                "任务 %s 的风险评估结果（共 %d 个断面）：\n高风险：%d 个 | 中风险：%d 个 | 低风险：%d 个\n\n详细数据：\n%s",
                taskId, rows.size(), counts[0], counts[1], counts[2], details);
    }

    /**
     * 单条断面行的文本形式。
     *
     * <p>**这是订阅契约的生产端**：报告工作流 Step 1 让模型原样返回本输出，随后
     * {@code ReportWorkflowService.parseRiskRows} 按正则把 名称 / ID / 等级 / 坐标
     * 解析回来。段与段之间用 {@code |} 分隔，任何一段都不能含 {@code |} 或 {@code （}。
     *
     * <p>ID 段是 Step 5 取剖面的唯一依据（见 {@link #sectionIdText}），
     * 删掉它会让报告里的断面图整批消失，且**不报错**。
     */
    public static String sectionLine(Map<String, Object> row, int level) {
        String sectionName = String.valueOf(row.getOrDefault("section_name", "未知"));
        String riskValue = String.valueOf(row.getOrDefault("risk_value", "无"));
        String bankName = String.valueOf(row.getOrDefault("bank_name", ""));
        return String.format("  - 断面：%s（%s）| ID：%s | 风险等级：%d | 评估指标：%s | 坐标：%s",
                sectionName, bankName, sectionIdText(row), level, riskValue, coordText(row));
    }

    /**
     * 断面起点坐标的文本形式：{@code (经度, 纬度)}，6 位小数；缺失时返回「未知」。
     *
     * <p>这段格式是**订阅契约**：报告工作流按 `坐标：(lng, lat)` 正则解析回坐标，
     * 用于「按经纬度分区汇总」与 Step 4 的天气查询。改格式要同步
     * {@code ReportWorkflowService.COORD_PATTERN}。
     */
    static String coordText(Map<String, Object> row) {
        Object lng = row.get("start_lng");
        Object lat = row.get("start_lat");
        if (lng instanceof Number l && lat instanceof Number a) {
            return String.format("(%.6f, %.6f)", l.doubleValue(), a.doubleValue());
        }
        return "未知";
    }

    /**
     * 断面 ID 的文本形式；缺失时返回「未知」。
     *
     * <p>这段同样是**订阅契约**：报告工作流 Step 5 要拿它去查 {@code section_profiles}
     * 生成断面图（{@code queryProfiles} 按 {@code section_id} 过滤）。
     *
     * <p>以前这里刻意没输出 ID，Step 5 的提示词只好写「ID 暂用断面名」，
     * 而库里 {@code section_name} 与 {@code section_id} 是两套值
     * （如「断面6」vs {@code sec-task-1782124930426-5}，实测 867 行里零重合）
     * ⇒ 查不到剖面 ⇒ 报告里两张断面图永远缺图。改格式要同步
     * {@code ReportWorkflowService.SECTION_ID_PATTERN}。
     */
    static String sectionIdText(Map<String, Object> row) {
        Object sectionId = row.get("section_id");
        if (sectionId == null) {
            return "未知";
        }
        String text = String.valueOf(sectionId).trim();
        return text.isEmpty() ? "未知" : text;
    }
}
