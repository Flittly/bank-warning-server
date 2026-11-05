package com.yangtze.bankwarning.ai.tool;

import com.yangtze.bankwarning.ai.service.VisualizationService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 「分幅参数已绑定」的风险分布图工具。
 *
 * <p>报告工作流要求**出图分区与报告分区是同一组数字**。若把 split_rows/split_cols/
 * split_tiles 写进提示词让模型转述，模型随时可能漏传或改坏 JSON（此前把这三个参数
 * 声明成必填时，Step 2 就是被 schema 校验整步静默降级、报告里一张图都没有）。
 *
 * <p>所以这里把分幅参数闭包进工具：模型只需要传 {@code task_id}，分幅参数由 Java 侧
 * 一次性绑定。工具返回里携带的「分区明细(JSON)」就是本次出图**实际生效**的
 * bbox，报告直接用这组边界做分区，两边不可能漂移。
 *
 * <p>工具名沿用 {@code generate_risk_distribution_map}：下游的
 * {@code ReportWorkflowService.appendChartsFromTrace} / {@code MarkdownReportService}
 * 都按这个名字认图表，改名会静默丢图。
 */
public class BoundRiskDistributionTool {

    private static final Logger log = LoggerFactory.getLogger(BoundRiskDistributionTool.class);

    private final VisualizationService vizService;
    private final Integer splitRows;
    private final Integer splitCols;
    private final String splitTiles;

    public BoundRiskDistributionTool(VisualizationService vizService,
                                     Integer splitRows, Integer splitCols, String splitTiles) {
        this.vizService = vizService;
        this.splitRows = splitRows;
        this.splitCols = splitCols;
        this.splitTiles = splitTiles;
    }

    @Tool(name = "generate_risk_distribution_map",
            description = "生成任务的风险分布图。已按经纬度分幅，无需也不接受分幅参数。")
    public String generateRiskDistributionMap(
            @ToolParam(name = "task_id", description = "任务ID") String taskId) {
        log.info("[tool] bound risk distribution map, taskId={}, split={}x{}",
                taskId, splitRows, splitCols);
        Map<String, Object> result = vizService.generateRiskMap(
                taskId, null, splitRows, splitCols, splitTiles);
        return VisualizationTools.formatResult(result);
    }
}
