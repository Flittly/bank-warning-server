package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private final LlmClient llmClient;
    private final Prompts prompts;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReportService(LlmClient llmClient, Prompts prompts,
                         JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String generateReport(String sectionId) {
        log.info("[report] generating for section={}", sectionId);

        Map<String, Object> result = jdbcTemplate.queryForMap(
                "SELECT r.*, cs.section_name, cs.section_geometry, " +
                "b.bank_name " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.section_id = ? AND r.deleted_at IS NULL ORDER BY r.id DESC LIMIT 1",
                sectionId
        );

        if (result == null) {
            throw new IllegalArgumentException("未找到断面风险结果: " + sectionId);
        }

        Map<String, Object> reportData = buildReportData(result);
        String userPrompt = prompts.buildReportPrompt(reportData);
        String report = llmClient.chat(prompts.getReportSystem(), userPrompt);

        log.info("[report] generated, length={}", report.length());
        return report;
    }

    public Map<String, String> generateTaskReports(String taskId) {
        log.info("[report] generating task summary for task={}", taskId);

        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT r.*, cs.section_name, cs.section_geometry, " +
                "b.bank_name " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.task_id = ? AND r.deleted_at IS NULL ORDER BY r.risk_level DESC, r.id",
                taskId
        );

        if (results.isEmpty()) {
            throw new IllegalArgumentException("任务没有风险评估结果: " + taskId);
        }

        // 构建汇总数据
        List<Map<String, Object>> allData = new ArrayList<>();
        for (Map<String, Object> result : results) {
            allData.add(buildReportData(result));
        }

        // 构建汇总提示词
        String userPrompt = buildTaskSummaryPrompt(taskId, allData);
        
        // 只调用一次 LLM
        String summaryReport = llmClient.chat(prompts.getReportSystem(), userPrompt);
        log.info("[report] task summary generated, length={}", summaryReport.length());

        // 返回结果
        Map<String, String> reports = new LinkedHashMap<>();
        reports.put("summary", summaryReport);
        
        // 返回各断面的简要信息和地理数据
        for (Map<String, Object> data : allData) {
            String sectionId = String.valueOf(data.get("section_id"));
            String sectionName = String.valueOf(data.getOrDefault("section_name", sectionId));
            Object riskValue = data.get("risk_value");
            Object riskLevel = data.get("risk_level");
            String location = String.valueOf(data.getOrDefault("location", ""));
            reports.put(sectionId, String.format("%s [%s]: 风险值=%s, 等级=%s级", 
                    sectionName, location, riskValue, riskLevel));
        }

        return reports;
    }

    private String buildTaskSummaryPrompt(String taskId, List<Map<String, Object>> allData) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下任务的所有断面风险评估数据，生成一份任务级汇总报告。\n\n");
        sb.append("【任务ID】").append(taskId).append("\n");
        sb.append("【断面数量】").append(allData.size()).append(" 个\n\n");

        // 统计信息
        long highRisk = allData.stream().filter(d -> {
            Object level = d.get("risk_level");
            return level instanceof Number n && n.intValue() >= 3;
        }).count();

        sb.append("【风险分布】\n");
        sb.append("- 高风险断面（3-4级）：").append(highRisk).append(" 个\n");
        sb.append("- 中低风险断面（1-2级）：").append(allData.size() - highRisk).append(" 个\n\n");

        sb.append("【各断面详细数据】\n\n");

        for (int i = 0; i < allData.size(); i++) {
            Map<String, Object> data = allData.get(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> rawValues = data.get("raw_values") instanceof Map ?
                (Map<String, Object>) data.get("raw_values") : Collections.emptyMap();
            @SuppressWarnings("unchecked")
            Map<String, Object> coords = data.get("coordinates") instanceof Map ?
                (Map<String, Object>) data.get("coordinates") : Collections.emptyMap();

            sb.append(String.format("断面 %d: %s\n", i + 1, data.get("section_name")));
            sb.append(String.format("  - 岸段: %s\n", data.get("bank_name")));
            sb.append(String.format("  - 坐标: (%s, %s) → (%s, %s)\n", 
                    coords.get("start_lng"), coords.get("start_lat"),
                    coords.get("end_lng"), coords.get("end_lat")));
            sb.append(String.format("  - 综合风险值: %s\n", data.get("risk_value")));
            sb.append(String.format("  - 风险等级: %s 级\n", data.get("risk_level")));
            sb.append(String.format("  - Ky=%s, PQ=%s, Zd=%s\n", rawValues.get("Ky"), rawValues.get("PQ"), rawValues.get("Zd")));
            sb.append(String.format("  - Sa=%s, Ln=%s, Zb=%s\n", rawValues.get("Sa"), rawValues.get("Ln"), rawValues.get("Zb")));
            sb.append(String.format("  - Dsed=%s\n\n", rawValues.get("Dsed")));
        }

        sb.append("请生成报告，包含：\n");
        sb.append("1. 任务概述（断面数量、地理位置分布、风险分布）\n");
        sb.append("2. 高风险断面重点分析（结合地理位置）\n");
        sb.append("3. 整体风险趋势判断\n");
        sb.append("4. 综合防治建议（考虑地理因素）\n");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildReportData(Map<String, Object> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("section_id", result.get("section_id"));
        data.put("section_name", result.get("section_name"));
        data.put("risk_level", result.get("risk_level"));
        
        // 地理信息
        data.put("bank_name", result.get("bank_name"));
        
        // 解析坐标
        String location = extractLocation(result);
        data.put("location", location);
        data.put("coordinates", extractCoordinates(result));
        
        // 解析指标
        String indicatorsJson = result.get("indicators") != null ? result.get("indicators").toString() : "{}";
        try {
            Map<String, Object> indicators = objectMapper.readValue(indicatorsJson, Map.class);
            data.put("risk_value", indicators.get("result"));
            data.put("raw_values", indicators.getOrDefault("raw_values", Collections.emptyMap()));
            data.put("thresholds", indicators.getOrDefault("thresholds", Collections.emptyMap()));
        } catch (JsonProcessingException e) {
            log.warn("[report] parse indicators failed", e);
            data.put("risk_value", null);
            data.put("raw_values", Collections.emptyMap());
            data.put("thresholds", Collections.emptyMap());
        }

        return data;
    }
    
    @SuppressWarnings("unchecked")
    private String extractLocation(Map<String, Object> result) {
        StringBuilder location = new StringBuilder();
        
        // 岸段名称
        String bankName = result.get("bank_name") != null ? result.get("bank_name").toString() : "";
        if (!bankName.isEmpty()) {
            location.append(bankName);
        }
        
        return location.toString();
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractCoordinates(Map<String, Object> result) {
        Map<String, Object> coords = new LinkedHashMap<>();
        
        // 从 section_geometry 提取坐标
        Object geometry = result.get("section_geometry");
        if (geometry instanceof Map) {
            Map<String, Object> geoMap = (Map<String, Object>) geometry;
            Object coordinates = geoMap.get("coordinates");
            if (coordinates instanceof List<?> coordList && coordList.size() >= 2) {
                // 起点
                Object start = coordList.get(0);
                if (start instanceof List<?> startCoord && startCoord.size() >= 2) {
                    coords.put("start_lng", startCoord.get(0));
                    coords.put("start_lat", startCoord.get(1));
                }
                // 终点
                Object end = coordList.get(coordList.size() - 1);
                if (end instanceof List<?> endCoord && endCoord.size() >= 2) {
                    coords.put("end_lng", endCoord.get(0));
                    coords.put("end_lat", endCoord.get(1));
                }
            }
        }
        
        return coords;
    }
}
