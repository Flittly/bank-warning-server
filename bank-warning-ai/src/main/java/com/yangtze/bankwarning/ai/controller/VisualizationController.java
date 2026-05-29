package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.VisualizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v0/bank/ai")
public class VisualizationController {

    private static final Logger log = LoggerFactory.getLogger(VisualizationController.class);
    private final VisualizationService visualizationService;

    public VisualizationController(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    /**
     * 生成风险分布图
     */
    @GetMapping("/viz/risk-map")
    public Map<String, Object> generateRiskMap(
            @RequestParam(name = "task_id", required = false) String taskId,
            @RequestParam(name = "bank_id", required = false) String bankId) {
        log.info("[api] risk map, taskId={}, bankId={}", taskId, bankId);
        return visualizationService.generateRiskMap(taskId, bankId);
    }

    /**
     * 生成冲淤热力图
     */
    @GetMapping("/viz/heatmap")
    public Map<String, Object> generateHeatmap(
            @RequestParam(name = "section_id", required = false) String sectionId,
            @RequestParam(name = "task_id", required = false) String taskId) {
        log.info("[api] heatmap, sectionId={}, taskId={}", sectionId, taskId);
        return visualizationService.generateHeatmap(sectionId, taskId);
    }

    /**
     * 生成断面对比图
     */
    @GetMapping("/viz/section")
    public Map<String, Object> generateSectionComparison(
            @RequestParam(name = "section_id", required = false) String sectionId,
            @RequestParam(name = "task_id", required = false) String taskId) {
        log.info("[api] section comparison, sectionId={}, taskId={}", sectionId, taskId);
        return visualizationService.generateSectionComparison(sectionId, taskId);
    }
}
