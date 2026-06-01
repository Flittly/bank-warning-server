package com.yangtze.bankwarning.ai.tool;

import com.yangtze.bankwarning.ai.service.VisualizationService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class VisualizationTools {

    private static final Logger log = LoggerFactory.getLogger(VisualizationTools.class);
    private final VisualizationService vizService;

    public VisualizationTools(VisualizationService vizService) {
        this.vizService = vizService;
    }

    @Tool(name = "generate_risk_distribution_map", description = "生成风险分布图，在地图上标记各断面的风险等级分布情况")
    public String generateRiskDistributionMap(
            @ToolParam(name = "task_id", description = "任务ID") String taskId) {
        log.info("[tool] generating risk distribution map, taskId={}", taskId);
        Map<String, Object> result = vizService.generateRiskMap(taskId, null);
        return formatResult(result);
    }

    @Tool(name = "generate_scour_heatmap", description = "生成冲淤热力图，显示断面河床高程的冲淤变化情况")
    public String generateScourHeatmap(
            @ToolParam(name = "section_id", description = "断面ID") String sectionId) {
        log.info("[tool] generating scour heatmap, sectionId={}", sectionId);
        Map<String, Object> result = vizService.generateHeatmap(sectionId, null);
        return formatResult(result);
    }

    @Tool(name = "generate_section_comparison_chart", description = "生成断面对比图，对比不同时间点的断面形态变化")
    public String generateSectionComparisonChart(
            @ToolParam(name = "section_id", description = "断面ID") String sectionId) {
        log.info("[tool] generating section comparison chart, sectionId={}", sectionId);
        Map<String, Object> result = vizService.generateSectionComparison(sectionId, null);
        return formatResult(result);
    }

    private String formatResult(Map<String, Object> result) {
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) {
            String filePath = (String) result.get("file_path");
            return "图表生成成功，文件路径：" + filePath;
        } else {
            String error = (String) result.getOrDefault("error", "未知错误");
            return "图表生成失败：" + error;
        }
    }
}
