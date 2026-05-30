package com.yangtze.bankwarning.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yangtze.bankwarning.ai.service.LlmClient.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 可视化工具定义（简化版）
 */
@Component
public class VisualizationToolDefinitions {

    private final ObjectMapper objectMapper;

    public VisualizationToolDefinitions(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ToolDefinition> getAllTools() {
        return List.of(
                riskMapTool(),
                heatmapTool(),
                sectionComparisonTool(),
                queryRiskDataTool()
        );
    }

    private ToolDefinition riskMapTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode taskId = objectMapper.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description", "任务ID");
        properties.set("task_id", taskId);
        params.set("properties", properties);
        params.set("required", objectMapper.createArrayNode().add("task_id"));

        return new ToolDefinition(
                "generate_risk_distribution_map",
                "生成风险分布图，显示各断面的风险等级分布。",
                params
        );
    }

    private ToolDefinition heatmapTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode sectionId = objectMapper.createObjectNode();
        sectionId.put("type", "string");
        sectionId.put("description", "断面ID");
        properties.set("section_id", sectionId);
        params.set("properties", properties);
        params.set("required", objectMapper.createArrayNode().add("section_id"));

        return new ToolDefinition(
                "generate_scour_heatmap",
                "生成冲淤变化热力图，显示河床高程变化。",
                params
        );
    }

    private ToolDefinition sectionComparisonTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode sectionId = objectMapper.createObjectNode();
        sectionId.put("type", "string");
        sectionId.put("description", "断面ID");
        properties.set("section_id", sectionId);
        params.set("properties", properties);
        params.set("required", objectMapper.createArrayNode().add("section_id"));

        return new ToolDefinition(
                "generate_section_comparison_chart",
                "生成断面对比图，对比不同时间点的断面形态变化。",
                params
        );
    }

    private ToolDefinition queryRiskDataTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode taskId = objectMapper.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description", "任务ID");
        properties.set("task_id", taskId);
        params.set("properties", properties);
        params.set("required", objectMapper.createArrayNode().add("task_id"));

        return new ToolDefinition(
                "query_risk_data",
                "查询风险评估数据，包括断面信息、风险等级、指标数据。",
                params
        );
    }
}
