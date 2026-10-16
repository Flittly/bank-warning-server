package com.yangtze.bankwarning.ai.tool;

import com.yangtze.bankwarning.ai.service.VisualizationService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class VisualizationTools {

    private static final Logger log = LoggerFactory.getLogger(VisualizationTools.class);
    private final VisualizationService vizService;

    public VisualizationTools(VisualizationService vizService) {
        this.vizService = vizService;
    }

    // 分幅三个参数必须 required = false：@ToolParam 默认 required = true，
    // 一旦声明成必填，只传 task_id 的调用方（报告工作流 Step 2 的提示词就是
    // 「必须且只能调用工具，参数 taskId 为：xxx」；AI 对话也常只给 task_id）
    // 会在 ToolExecutor 的 JSON Schema 校验处被直接拒掉，工具只能返回 error。
    // 缺省即「不分幅」，与 VisualizationService.buildSplitConfig 的语义一致。
    @Tool(name = "generate_risk_distribution_map", description = "生成风险分布图，在地图上标记各断面的风险等级分布情况。"
            + "支持按经纬度分幅出图：split_rows/split_cols 指定行×列分块数（某一方向传 1 即不分），"
            + "split_tiles 可逐幅自定义经纬度边界（JSON 数组，如 [{\"row\":0,\"col\":1,\"west\":119.45}]），"
            + "分幅后每幅都是完整的风险分布图")
    public String generateRiskDistributionMap(
            @ToolParam(name = "task_id", description = "任务ID") String taskId,
            @ToolParam(name = "split_rows", required = false,
                    description = "纬度方向分块数，1=不分块（缺省1）") Integer splitRows,
            @ToolParam(name = "split_cols", required = false,
                    description = "经度方向分块数，1=不分块（缺省1）") Integer splitCols,
            @ToolParam(name = "split_tiles", required = false,
                    description = "逐幅自定义经纬度边界的 JSON 数组，"
                            + "每项可含 row/col 与 west/south/east/north，缺省的边沿用均分网格的对应边界") String splitTiles) {
        log.info("[tool] generating risk distribution map, taskId={}, split={}x{}, tiles={}",
                taskId, splitRows, splitCols, splitTiles);
        Map<String, Object> result = vizService.generateRiskMap(
                taskId, null, splitRows, splitCols, splitTiles);
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

    /**
     * 把出图结果渲染成给 LLM / 工作流看的文本。
     *
     * <p>公开静态：{@link BoundRiskDistributionTool}（分幅参数已绑定版）复用同一份
     * 格式化器；报告工作流的契约测试也要跨包调用它来验证「分区明细(JSON)」
     * 这段订阅契约的产出/消费两端对得上。改格式必须同步
     * {@code ReportWorkflowService.partitionsFrom}。
     */
    public static String formatResult(Map<String, Object> result) {
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) {
            // 分幅出图：Python 返回 file_paths（多幅）；单图只有 file_path
            Object paths = result.get("file_paths");
            if (paths instanceof List<?> list && !list.isEmpty()) {
                Object count = result.getOrDefault("tile_count", list.size());
                String head = "图表生成成功，共 " + count + " 幅子图，文件路径："
                        + list.stream().map(String::valueOf)
                        .reduce((a, b) -> a + ", " + b).orElse("");
                return head + "\n" + partitionsJson(result);
            }
            String filePath = (String) result.get("file_path");
            return "图表生成成功，文件路径：" + filePath;
        } else {
            String error = (String) result.getOrDefault("error", "未知错误");
            return "图表生成失败：" + error;
        }
    }

    /**
     * 分幅出的每幅子图，各自用的是哪一组经纬度边界——**必须原样回传**。
     *
     * <p>报告工作流靠这一行把断面归入分区，用的就是出图实际生效的 bbox（而不是在
     * Java 侧另算一套），这是「报告分区与出图分区保持一致」的唯一保证。
     * 格式固定为一行 JSON，前缀 {@code 分区明细(JSON)：}；解析方
     * {@code ReportWorkflowService.partitionsFrom()} 依赖该前缀与字段名。
     */
    private static String partitionsJson(Map<String, Object> result) {
        Object raw = result.get("tiles");
        if (!(raw instanceof List<?> tiles) || tiles.isEmpty()) {
            return "分区明细(JSON)：[]";
        }
        StringBuilder sb = new StringBuilder("分区明细(JSON)：[");
        boolean first = true;
        for (Object item : tiles) {
            if (!(item instanceof Map<?, ?> tile) || !Boolean.TRUE.equals(tile.get("success"))) {
                continue;
            }
            Object bbox = tile.get("bbox");
            if (!(bbox instanceof List<?> box) || box.size() != 4) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            Object row = tile.get("row");
            Object col = tile.get("col");
            Object sections = tile.get("sections_in_tile");
            sb.append(String.format(java.util.Locale.ROOT,
                    "{\"row\":%s,\"col\":%s,\"west\":%.6f,\"south\":%.6f,"
                            + "\"east\":%.6f,\"north\":%.6f,\"sections\":%s,\"file_name\":\"%s\"}",
                    row == null ? "0" : String.valueOf(row),
                    col == null ? "0" : String.valueOf(col),
                    ((Number) box.get(0)).doubleValue(), ((Number) box.get(1)).doubleValue(),
                    ((Number) box.get(2)).doubleValue(), ((Number) box.get(3)).doubleValue(),
                    sections == null ? "0" : String.valueOf(sections),
                    fileNameOf(tile.get("file_path"))));
        }
        return sb.append(']').toString();
    }

    private static String fileNameOf(Object path) {
        if (path == null) {
            return "";
        }
        String text = String.valueOf(path);
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        return slash >= 0 ? text.substring(slash + 1) : text;
    }
}
