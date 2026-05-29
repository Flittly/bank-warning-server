package com.yangtze.bankwarning.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.service.VisualizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 可视化工具执行器
 */
@Component
public class VisualizationToolExecutors {

    private static final Logger log = LoggerFactory.getLogger(VisualizationToolExecutors.class);
    private final VisualizationService visualizationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public VisualizationToolExecutors(VisualizationService visualizationService, 
                                       JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.visualizationService = visualizationService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取所有工具执行器
     */
    public Map<String, Function<Map<String, String>, String>> getAllExecutors() {
        Map<String, Function<Map<String, String>, String>> executors = new LinkedHashMap<>();
        executors.put("generate_risk_distribution_map", this::executeRiskMap);
        executors.put("generate_scour_heatmap", this::executeHeatmap);
        executors.put("generate_section_comparison_chart", this::executeSectionComparison);
        executors.put("query_risk_data", this::executeQueryRiskData);
        return executors;
    }

    /**
     * 执行风险分布图生成
     */
    private String executeRiskMap(Map<String, String> args) {
        String taskId = args.get("task_id");
        String bankId = args.get("bank_id");
        log.info("[tool] generating risk map, taskId={}, bankId={}", taskId, bankId);

        Map<String, Object> result = visualizationService.generateRiskMap(taskId, bankId);
        return toJson(result);
    }

    /**
     * 执行冲淤热力图生成
     */
    private String executeHeatmap(Map<String, String> args) {
        String sectionId = args.get("section_id");
        String taskId = args.get("task_id");
        log.info("[tool] generating heatmap, sectionId={}, taskId={}", sectionId, taskId);

        Map<String, Object> result = visualizationService.generateHeatmap(sectionId, taskId);
        return toJson(result);
    }

    /**
     * 执行断面对比图生成
     */
    private String executeSectionComparison(Map<String, String> args) {
        String sectionId = args.get("section_id");
        String taskId = args.get("task_id");
        log.info("[tool] generating section comparison, sectionId={}, taskId={}", sectionId, taskId);

        Map<String, Object> result = visualizationService.generateSectionComparison(sectionId, taskId);
        return toJson(result);
    }

    /**
     * 执行风险数据查询
     */
    private String executeQueryRiskData(Map<String, String> args) {
        String taskId = args.get("task_id");
        String bankId = args.get("bank_id");
        String sectionId = args.get("section_id");
        log.info("[tool] querying risk data, taskId={}, bankId={}, sectionId={}", taskId, bankId, sectionId);

        StringBuilder sql = new StringBuilder(
                "SELECT r.section_id, cs.section_name, r.risk_level, " +
                "r.indicators->>'result' as risk_value, " +
                "b.bank_name " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.deleted_at IS NULL"
        );

        List<Object> params = new java.util.ArrayList<>();
        if (taskId != null) {
            sql.append(" AND r.task_id = ?");
            params.add(taskId);
        }
        if (bankId != null) {
            sql.append(" AND cs.bank_id = ?");
            params.add(bankId);
        }
        if (sectionId != null) {
            sql.append(" AND r.section_id = ?");
            params.add(sectionId);
        }
        sql.append(" ORDER BY r.risk_level DESC LIMIT 20");

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("count", results.size());
        response.put("data", results);
        
        return toJson(response);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"success\": false, \"error\": \"JSON serialization failed\"}";
        }
    }
}
