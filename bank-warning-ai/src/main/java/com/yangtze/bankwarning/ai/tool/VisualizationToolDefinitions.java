package com.yangtze.bankwarning.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yangtze.bankwarning.ai.service.LlmClient.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 可视化工具定义
 */
@Component
public class VisualizationToolDefinitions {

    private final ObjectMapper objectMapper;

    public VisualizationToolDefinitions(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 获取所有可视化工具定义
     */
    public List<ToolDefinition> getAllTools() {
        return List.of(
                riskMapTool(),
                heatmapTool(),
                sectionComparisonTool(),
                queryRiskDataTool()
        );
    }

    /**
     * 风险分布图工具
     */
    private ToolDefinition riskMapTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode taskId = objectMapper.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description", "任务ID，用于筛选特定任务的风险数据");
        properties.set("task_id", taskId);

        ObjectNode bankId = objectMapper.createObjectNode();
        bankId.put("type", "string");
        bankId.put("description", "岸段ID，用于筛选特定岸段的风险数据");
        properties.set("bank_id", bankId);

        params.set("properties", properties);

        return new ToolDefinition(
                "generate_risk_distribution_map",
                "生成岸段风险分布图，在地图上显示各断面的风险等级分布。当用户询问风险分布、风险地图、各断面风险情况时调用此工具。",
                params
        );
    }

    /**
     * 冲淤热力图工具
     */
    private ToolDefinition heatmapTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode sectionId = objectMapper.createObjectNode();
        sectionId.put("type", "string");
        sectionId.put("description", "断面ID，用于生成特定断面的冲淤热力图");
        properties.set("section_id", sectionId);

        ObjectNode taskId = objectMapper.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description", "任务ID，用于生成任务下所有断面的冲淤热力图");
        properties.set("task_id", taskId);

        params.set("properties", properties);

        return new ToolDefinition(
                "generate_scour_heatmap",
                "生成冲淤变化热力图，显示河床高程变化情况。当用户询问冲淤变化、河床变化、高程变化时调用此工具。",
                params
        );
    }

    /**
     * 断面对比图工具
     */
    private ToolDefinition sectionComparisonTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode sectionId = objectMapper.createObjectNode();
        sectionId.put("type", "string");
        sectionId.put("description", "断面ID，用于对比该断面在不同时间点的形态变化");
        properties.set("section_id", sectionId);

        ObjectNode taskId = objectMapper.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description", "任务ID，用于对比任务下各断面的形态");
        properties.set("task_id", taskId);

        params.set("properties", properties);

        return new ToolDefinition(
                "generate_section_comparison_chart",
                "生成断面对比图，对比不同时间点的断面形态变化。当用户询问断面变化、断面对比、断面形态时调用此工具。",
                params
        );
    }

    /**
     * 风险数据查询工具
     */
    private ToolDefinition queryRiskDataTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode taskId = objectMapper.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description", "任务ID");
        properties.set("task_id", taskId);

        ObjectNode bankId = objectMapper.createObjectNode();
        bankId.put("type", "string");
        bankId.put("description", "岸段ID");
        properties.set("bank_id", bankId);

        ObjectNode sectionId = objectMapper.createObjectNode();
        sectionId.put("type", "string");
        sectionId.put("description", "断面ID");
        properties.set("section_id", sectionId);

        params.set("properties", properties);

        return new ToolDefinition(
                "query_risk_data",
                "查询风险评估数据，包括断面信息、风险等级、指标数据等。当用户询问具体的风险数据、指标数值时调用此工具。",
                params
        );
    }
}
