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

        String sql = "SELECT r.section_id, cs.section_name, r.risk_level, " +
                "r.indicators->>'result' as risk_value, b.bank_name " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.task_id = ? AND r.deleted_at IS NULL " +
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

            String sectionName = String.valueOf(row.getOrDefault("section_name", "未知"));
            String riskValue = String.valueOf(row.getOrDefault("risk_value", "无"));
            String bankName = String.valueOf(row.getOrDefault("bank_name", ""));
            return String.format("  - 断面：%s（%s）| 风险等级：%d | 评估指标：%s",
                    sectionName, bankName, level, riskValue);
        }).collect(Collectors.joining("\n"));

        return String.format(
                "任务 %s 的风险评估结果（共 %d 个断面）：\n高风险：%d 个 | 中风险：%d 个 | 低风险：%d 个\n\n详细数据：\n%s",
                taskId, rows.size(), counts[0], counts[1], counts[2], details);
    }
}
