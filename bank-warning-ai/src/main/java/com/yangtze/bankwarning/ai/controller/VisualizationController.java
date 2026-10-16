package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.VisualizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    /**
     * 批量出图。
     *
     * <p>请求体：{@code {"taskId":"...", "mode":"merged|per_bank", "bankIds":["..."]}}
     * <ul>
     *   <li>merged  —— 整任务全部断面合并为一张图（等价现状）</li>
     *   <li>per_bank —— 每个岸段各出一张图；bankIds 为空表示该任务下全部有结果的岸段</li>
     *   <li>splitRows/splitCols/splitTiles 均可选：不传 = 不分幅（历史行为）；
     *       传入后每张图再按经纬度切成 rows×cols 幅子图，返回项带 images[] 多幅明细</li>
     * </ul>
     * 返回体含 images[]，每项带可直接访问的静态 URL（/v0/bank/ai/viz-output/&lt;dir&gt;/&lt;file&gt;）。
     */
    @PostMapping("/viz/risk-map/batch")
    public Map<String, Object> generateRiskMapBatch(@RequestBody(required = false) Map<String, Object> body) {
        String taskId = SplitParams.asString(body == null ? null : body.get("taskId"));
        String mode = SplitParams.asString(body == null ? null : body.get("mode"));
        List<String> bankIds = SplitParams.asStringList(body == null ? null : body.get("bankIds"));
        Integer splitRows;
        Integer splitCols;
        try {
            splitRows = SplitParams.asInteger(body == null ? null : body.get("splitRows"));
            splitCols = SplitParams.asInteger(body == null ? null : body.get("splitCols"));
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
        String splitTiles = SplitParams.asString(body == null ? null : body.get("splitTiles"));
        log.info("[api] risk map batch, taskId={}, mode={}, bankCount={}, split={}x{}, tiles={}",
                taskId, mode, bankIds.size(), splitRows, splitCols,
                splitTiles == null || splitTiles.isBlank() ? "无" : "自定义");
        return visualizationService.generateRiskMapBatch(
                taskId, mode, bankIds, splitRows, splitCols, splitTiles);
    }
}
