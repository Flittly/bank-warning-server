package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.prompt.Prompts;
import com.yangtze.bankwarning.ai.tool.VisualizationToolDefinitions;
import com.yangtze.bankwarning.ai.tool.VisualizationToolExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Agent 驱动的报告生成服务
 * LLM 自主决定是否生成图表
 */
@Service
public class AgentReportService {

    private static final Logger log = LoggerFactory.getLogger(AgentReportService.class);
    private final LlmClient llmClient;
    private final Prompts prompts;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final VisualizationToolDefinitions toolDefinitions;
    private final VisualizationToolExecutors toolExecutors;

    public AgentReportService(LlmClient llmClient, Prompts prompts,
                               JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                               VisualizationToolDefinitions toolDefinitions,
                               VisualizationToolExecutors toolExecutors) {
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.toolDefinitions = toolDefinitions;
        this.toolExecutors = toolExecutors;
    }

    /**
     * Agent 驱动的报告生成
     * LLM 自主决定是否需要生成图表
     */
    public Map<String, Object> generateAgentReport(String sectionId) {
        log.info("[agent-report] generating for section={}", sectionId);

        // 1. 查询断面数据
        Map<String, Object> sectionData = querySectionData(sectionId);
        if (sectionData.isEmpty()) {
            return Map.of("success", false, "error", "未找到断面数据: " + sectionId);
        }

        // 2. 构建提示词
        String userPrompt = buildAgentPrompt(sectionData);

        // 3. 调用 LLM（带工具）
        LlmClient.AgentResponse response = llmClient.chatWithTools(
                prompts.getReportSystem(),
                userPrompt,
                toolDefinitions.getAllTools(),
                toolExecutors.getAllExecutors()
        );

        // 4. 组装结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("section_id", sectionId);
        result.put("report", response.content());

        // 收集生成的图表
        List<Map<String, String>> charts = new ArrayList<>();
        for (LlmClient.ToolCallResult toolResult : response.toolResults()) {
            if (toolResult.functionName().startsWith("generate_")) {
                Map<String, String> chart = new LinkedHashMap<>();
                chart.put("tool", toolResult.functionName());
                chart.put("result", toolResult.result());
                charts.add(chart);
            }
        }
        result.put("charts", charts);
        result.put("tool_calls_count", response.toolResults().size());

        log.info("[agent-report] generated with {} tool calls", response.toolResults().size());
        return result;
    }

    /**
     * Agent 驱动的任务报告生成
     */
    public Map<String, Object> generateAgentTaskReport(String taskId) {
        log.info("[agent-report] generating for task={}", taskId);

        // 1. 查询任务数据
        List<Map<String, Object>> taskData = queryTaskData(taskId);
        if (taskData.isEmpty()) {
            return Map.of("success", false, "error", "未找到任务数据: " + taskId);
        }

        // 2. 构建提示词
        String userPrompt = buildTaskAgentPrompt(taskId, taskData);

        // 3. 调用 LLM（带工具）
        LlmClient.AgentResponse response = llmClient.chatWithTools(
                prompts.getReportSystem(),
                userPrompt,
                toolDefinitions.getAllTools(),
                toolExecutors.getAllExecutors()
        );

        // 4. 组装结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        result.put("report", response.content());

        // 收集生成的图表
        List<Map<String, String>> charts = new ArrayList<>();
        for (LlmClient.ToolCallResult toolResult : response.toolResults()) {
            if (toolResult.functionName().startsWith("generate_")) {
                Map<String, String> chart = new LinkedHashMap<>();
                chart.put("tool", toolResult.functionName());
                chart.put("result", toolResult.result());
                charts.add(chart);
            }
        }
        result.put("charts", charts);
        result.put("sections_count", taskData.size());
        result.put("tool_calls_count", response.toolResults().size());

        log.info("[agent-report] generated task report with {} tool calls", response.toolResults().size());
        return result;
    }

    /**
     * 查询断面数据
     */
    private Map<String, Object> querySectionData(String sectionId) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT r.*, cs.section_name, b.bank_name, " +
                "ST_X(ST_StartPoint(cs.geom)) as start_lng, " +
                "ST_Y(ST_StartPoint(cs.geom)) as start_lat, " +
                "ST_X(ST_EndPoint(cs.geom)) as end_lng, " +
                "ST_Y(ST_EndPoint(cs.geom)) as end_lat " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.section_id = ? AND r.deleted_at IS NULL " +
                "ORDER BY r.id DESC LIMIT 1",
                sectionId
        );
        return results.isEmpty() ? Collections.emptyMap() : results.get(0);
    }

    /**
     * 查询任务数据
     */
    private List<Map<String, Object>> queryTaskData(String taskId) {
        return jdbcTemplate.queryForList(
                "SELECT r.*, cs.section_name, b.bank_name, " +
                "ST_X(ST_StartPoint(cs.geom)) as start_lng, " +
                "ST_Y(ST_StartPoint(cs.geom)) as start_lat, " +
                "ST_X(ST_EndPoint(cs.geom)) as end_lng, " +
                "ST_Y(ST_EndPoint(cs.geom)) as end_lat " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.task_id = ? AND r.deleted_at IS NULL " +
                "ORDER BY r.risk_level DESC",
                taskId
        );
    }

    /**
     * 构建单断面 Agent 提示词
     */
    @SuppressWarnings("unchecked")
    private String buildAgentPrompt(Map<String, Object> data) {
        String sectionName = String.valueOf(data.getOrDefault("section_name", "未知断面"));
        String bankName = String.valueOf(data.getOrDefault("bank_name", ""));
        Object riskLevel = data.get("risk_level");

        String indicatorsJson = data.get("indicators") != null ? data.get("indicators").toString() : "{}";
        Map<String, Object> indicators = Collections.emptyMap();
        try {
            indicators = objectMapper.readValue(indicatorsJson, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("[agent-report] parse indicators failed", e);
        }

        return String.format("""
                请为以下断面生成风险评估报告。
                
                【断面信息】
                - 断面名称：%s
                - 所属岸段：%s
                - 风险等级：%s 级
                
                【指标数据】
                %s
                
                请根据数据情况：
                1. 如果数据包含地理位置信息，建议生成风险分布图
                2. 如果需要展示断面形态变化，建议生成断面对比图
                3. 生成详细的文字分析报告
                
                断面ID: %s
                """,
                sectionName, bankName, riskLevel,
                formatIndicators(indicators),
                data.get("section_id")
        );
    }

    /**
     * 构建任务级 Agent 提示词
     */
    private String buildTaskAgentPrompt(String taskId, List<Map<String, Object>> taskData) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下任务生成风险评估汇总报告。\n\n");
        sb.append("【任务ID】").append(taskId).append("\n");
        sb.append("【断面数量】").append(taskData.size()).append(" 个\n\n");

        // 统计风险分布
        long highRisk = taskData.stream()
                .filter(d -> d.get("risk_level") instanceof Number n && n.intValue() >= 3)
                .count();

        sb.append("【风险分布】\n");
        sb.append("- 高风险（3-4级）：").append(highRisk).append(" 个\n");
        sb.append("- 中低风险（1-2级）：").append(taskData.size() - highRisk).append(" 个\n\n");

        sb.append("【各断面数据】\n");
        for (int i = 0; i < Math.min(taskData.size(), 10); i++) {
            Map<String, Object> d = taskData.get(i);
            sb.append(String.format("%d. %s - 风险等级: %s级\n",
                    i + 1, d.get("section_name"), d.get("risk_level")));
        }
        if (taskData.size() > 10) {
            sb.append("... 共 ").append(taskData.size()).append(" 个断面\n");
        }

        sb.append("\n请根据数据情况：\n");
        sb.append("1. 生成风险分布图展示各断面风险等级\n");
        sb.append("2. 生成冲淤热力图展示河床变化\n");
        sb.append("3. 生成详细的汇总分析报告\n");

        return sb.toString();
    }

    /**
     * 格式化指标数据
     */
    @SuppressWarnings("unchecked")
    private String formatIndicators(Map<String, Object> indicators) {
        if (indicators.isEmpty()) return "无指标数据";

        Map<String, Object> rawValues = indicators.get("raw_values") instanceof Map ?
                (Map<String, Object>) indicators.get("raw_values") : Collections.emptyMap();

        StringBuilder sb = new StringBuilder();
        sb.append("- 综合风险值: ").append(indicators.get("result")).append("\n");
        sb.append("- 水动力: Ky=").append(rawValues.get("Ky"))
          .append(", PQ=").append(rawValues.get("PQ"))
          .append(", Zd=").append(rawValues.get("Zd")).append("\n");
        sb.append("- 河床演变: Sa=").append(rawValues.get("Sa"))
          .append(", Ln=").append(rawValues.get("Ln"))
          .append(", Zb=").append(rawValues.get("Zb")).append("\n");
        sb.append("- 地质工程: Dsed=").append(rawValues.get("Dsed"));

        return sb.toString();
    }
}
