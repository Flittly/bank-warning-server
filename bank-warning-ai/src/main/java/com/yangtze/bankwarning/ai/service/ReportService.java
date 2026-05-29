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
                "SELECT r.*, cs.section_name FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
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
        log.info("[report] generating for task={}", taskId);

        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT r.*, cs.section_name FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "WHERE r.task_id = ? AND r.deleted_at IS NULL ORDER BY r.id",
                taskId
        );

        Map<String, String> reports = new LinkedHashMap<>();
        for (Map<String, Object> result : results) {
            String sectionId = result.get("section_id").toString();
            try {
                Map<String, Object> reportData = buildReportData(result);
                String userPrompt = prompts.buildReportPrompt(reportData);
                String report = llmClient.chat(prompts.getReportSystem(), userPrompt);
                reports.put(sectionId, report);
            } catch (Exception e) {
                log.error("[report] failed for section={}", sectionId, e);
                reports.put(sectionId, "报告生成失败: " + e.getMessage());
            }
        }

        log.info("[report] generated {} reports", reports.size());
        return reports;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildReportData(Map<String, Object> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("section_id", result.get("section_id"));
        data.put("section_name", result.get("section_name"));
        data.put("risk_level", result.get("risk_level"));

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
}
