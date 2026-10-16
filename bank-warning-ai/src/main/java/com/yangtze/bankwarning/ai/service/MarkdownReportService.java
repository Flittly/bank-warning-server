package com.yangtze.bankwarning.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 报告生成服务
 * 将报告文字和图表整合到一个 Markdown 文件中
 */
@Service
public class MarkdownReportService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownReportService.class);

    /** 任务输出目录名：{@code report_<taskId>_<yyyyMMdd_HHmmss>}。 */
    private static final Pattern TASK_DIR_TIMESTAMP_PTN =
            Pattern.compile("^report_.+_(\\d{8}_\\d{6})$");

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String outputDir;

    /**
     * 生成包含图表的 Markdown 报告
     */
    public String generateMarkdownReport(String taskId, String reportText, List<Map<String, String>> charts) {
        return generateMarkdownReport(taskId, reportText, charts, null);
    }

    /**
     * 生成包含图表与**分区断面详情 CSV 清单**的 Markdown 报告。
     *
     * @param sectionCsvs 本次随报告导出的分区 CSV；不分幅时为 null 或空
     */
    public String generateMarkdownReport(String taskId, String reportText,
                                         List<Map<String, String>> charts,
                                         PartitionSectionCsvService.CsvExport sectionCsvs) {
        String content = buildMarkdownContent(taskId, reportText, charts, sectionCsvs);
        String mdFilePath = saveMarkdownFile(taskId, content);
        log.info("[markdown] report saved to: {}", mdFilePath);
        return mdFilePath;
    }

    public String buildMarkdownContent(String taskId, String reportText, List<Map<String, String>> charts) {
        return buildMarkdownContent(taskId, reportText, charts, null);
    }

    public String buildMarkdownContent(String taskId, String reportText,
                                       List<Map<String, String>> charts,
                                       PartitionSectionCsvService.CsvExport sectionCsvs) {
        log.info("[markdown] building content for task={}", taskId);

        StringBuilder md = new StringBuilder();
        
        // 报告标题
        md.append("# 长江河岸崩塌风险评估报告\n\n");
        md.append("**任务ID**: ").append(taskId).append("\n\n");
        md.append("**生成时间**: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        md.append("---\n\n");
        
        // 报告正文
        md.append(reportText).append("\n\n");
        
        // 如果有图表，添加图表部分
        if (charts != null && !charts.isEmpty()) {
            appendChartsAppendix(md, charts);
        } else {
            appendDirectoryImages(md);
        }

        appendSectionCsvAppendix(md, sectionCsvs);

        return md.toString();
    }

    /**
     * 分区断面详情 CSV 清单。
     *
     * <p>导出报告时这些 CSV 会与 docx 打进同一个压缩包，这里把它们列进报告正文的附录，
     * 让读者知道「每个分区对应哪个 CSV 文件」。
     */
    private void appendSectionCsvAppendix(StringBuilder md,
                                          PartitionSectionCsvService.CsvExport sectionCsvs) {
        if (sectionCsvs == null || sectionCsvs.isEmpty()) {
            return;
        }
        md.append("---\n\n");
        md.append("## 附录：分区断面详情（CSV）\n\n");
        md.append("本次报告按经纬度划分为 ").append(sectionCsvs.partitions().size())
                .append(" 个分区，各分区的断面详情在导出报告时随报告一并输出为独立的 CSV 文件：\n\n");
        md.append("| 分区 | 分区位置 | 断面数 | CSV 文件 |\n");
        md.append("| --- | --- | --- | --- |\n");
        for (PartitionSectionCsvService.PartitionCsv csv : sectionCsvs.partitions()) {
            md.append("| 分区 ").append(csv.index())
                    .append(" | ").append(csv.label())
                    .append(" | ").append(csv.sectionCount())
                    .append(" | `").append(csv.fileName()).append("` |\n");
        }
        if (sectionCsvs.unassignedCount() > 0) {
            md.append("| 未落入分区 | 坐标缺失或落在自定义边界之外 | ")
                    .append(sectionCsvs.unassignedCount())
                    .append(" | `").append(sectionCsvs.unassignedFile()).append("` |\n");
        }
        md.append('\n');
        md.append("> 分区边界与「风险分布图」的分幅边界完全相同（同一组经纬度范围），"
                + "断面按**起点坐标**归属，边界含边；自定义分幅出现重叠时，"
                + "同一条断面会同时出现在多个分区的 CSV 中。\n\n");
    }

    /**
     * 图表附录。
     *
     * <p>分幅出图时每张地图都带着它所属的分区标签（由
     * {@code ReportWorkflowService.appendPartitionTag} 打上），附录按分区归组，
     * 分区标题的序号/经纬范围与报告正文的小节一一对应。
     * 无分区信息时行为与历史版本完全一致（### 图种）。
     */
    private void appendChartsAppendix(StringBuilder md, List<Map<String, String>> charts) {
        md.append("---\n\n");
        md.append("## 附录：评估图表\n\n");

        boolean partitioned = charts.stream().anyMatch(chart -> chart.get("partitionKey") != null);
        if (!partitioned) {
            for (Map<String, String> chart : charts) {
                appendChart(md, chart, "###");
            }
            return;
        }

        // 按分区归组（保持首次出现顺序）；无分区信息的图表归到最后一组
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (Map<String, String> chart : charts) {
            groups.computeIfAbsent(chart.getOrDefault("partitionKey", ""),
                    key -> new ArrayList<>()).add(chart);
        }
        for (Map.Entry<String, List<Map<String, String>>> group : groups.entrySet()) {
            List<Map<String, String>> items = group.getValue();
            if (group.getKey().isEmpty()) {
                md.append("### 其他图表\n\n");
            } else {
                Map<String, String> first = items.get(0);
                md.append("### 分区 ").append(first.getOrDefault("partitionIndex", ""))
                        .append(" · ").append(first.getOrDefault("partitionLabel", ""))
                        .append("\n\n");
            }
            for (Map<String, String> chart : items) {
                appendChart(md, chart, "####");
            }
        }
    }

    private void appendChart(StringBuilder md, Map<String, String> chart, String heading) {
        String filePath = extractFilePath(chart.get("result"));
        if (filePath == null) {
            return;
        }
        String chartTitle = getChartTitle(chart.get("tool"));
        md.append(heading).append(' ').append(chartTitle).append("\n\n");
        md.append("![").append(chartTitle).append("](")
                .append(getRelativePath(filePath)).append(")\n\n");
    }

    private void appendDirectoryImages(StringBuilder md) {
        String taskDir = VisualizationService.getCurrentTaskOutputDir();
        if (taskDir == null) return;
        java.io.File dir = new java.io.File(taskDir);
        java.io.File[] pngs = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".png"));
        if (pngs == null || pngs.length == 0) return;
        md.append("---\n\n");
        md.append("## 附录：评估图表\n\n");
        for (java.io.File png : pngs) {
            String name = png.getName();
            String title = name.replace(".png", "").replace("_", " ");
            String folder = dir.getName();
            md.append("### ").append(title).append("\n\n");
            md.append("![").append(title).append("](./").append(folder).append("/").append(name).append(")\n\n");
        }
    }

    /** 图片扩展名——用来判定「一条路径到哪里为止」。 */
    private static final Pattern IMAGE_EXT_PTN =
            Pattern.compile("\\.(png|jpg|jpeg|gif|svg|webp)", Pattern.CASE_INSENSITIVE);

    /**
     * 「值」的终止字符：真换行 / 回车 / 引号 / 方括号。
     *
     * <p>反斜杠**不在**其中——Windows 路径本身就靠反斜杠分段，把它当终止符会让
     * 所有路径在第一个分段后就断掉。
     *
     * <p>也**不**把「字面量 {@code \n}」当终止符：它确实可能出现（进 trace 日志时
     * 换行被转义成反斜杠 + n），但 `…\new_folder\a.png` 这种真实路径里也含 `\n`，
     * 一旦当成终止符就会被切掉。真正兜住这种情况的是方括号 —— 紧随路径之后的
     * 分区明细行必定是 {@code 分区明细(JSON)：[…}，`[` 天然就是干净的边界。
     */
    private static final char[] VALUE_STOP_CHARS = {'\n', '\r', '"', '[', ']'};

    /** 分区明细行的前缀：值截断的第二道保险，避免把这一行带进路径。 */
    private static final String PARTITION_MARKER = "分区明细(JSON)：";

    /** 从工具返回中提取第一个文件路径；取不到返回 null。多幅场景请用 {@link #extractFilePaths}。 */
    public static String extractFilePath(String resultJson) {
        List<String> paths = extractFilePaths(resultJson);
        return paths.isEmpty() ? null : paths.get(0);
    }

    /**
     * 从工具返回中提取**全部**文件路径（按出现顺序、去重）。
     *
     * <p>支持三种来源，与 {@code VisualizationTools.formatResult} 的三种产出对应：
     * <ol>
     *   <li>结构化：{@code {"file_path":"…"}}（{@code appendChartsFromTrace} 落库用的形式）；</li>
     *   <li>纯文本单图：{@code 图表生成成功，文件路径：<abs>}；</li>
     *   <li>纯文本分幅：{@code 图表生成成功，共 N 幅子图，文件路径：<abs1>, <abs2>, …}
     *       后面紧跟一行「分区明细(JSON)：[…]」。</li>
     * </ol>
     *
     * <p>分幅场景**必须返回 N 条**：只返回第一条会让报告附录丢掉 N-1 张图，
     * 而且那一条还会因为路径粘上了分区行而变成坏链接。
     *
     * <p>第四种兜底：文本里裸出现的第一条带图片扩展名的片段。
     */
    public static List<String> extractFilePaths(String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) {
            return List.of();
        }

        // 1) 结构化 {"file_path":"…"}
        int json = resultJson.indexOf("\"file_path\":\"");
        String payload = json >= 0
                ? valueAfter(resultJson, json + "\"file_path\":\"".length())
                : null;
        // 2) 纯文本「文件路径：…」
        if (payload == null) {
            int idx = resultJson.indexOf("文件路径：");
            if (idx >= 0) {
                payload = valueAfter(resultJson, idx + "文件路径：".length());
            }
        }
        List<String> paths = payload == null ? new ArrayList<>() : splitPaths(payload);
        if (!paths.isEmpty()) {
            return paths;
        }

        // 3) 兜底：文本里裸出现的第一条图片路径
        Matcher m = IMAGE_EXT_PTN.matcher(resultJson);
        if (m.find()) {
            String token = trimToImageEnd(resultJson.substring(0, m.end()));
            if (token != null) {
                return List.of(token);
            }
        }
        return List.of();
    }

    /**
     * 取从 {@code from} 起的「值」——遇到真换行 / 引号 / 方括号 / 分区明细前缀即止。
     *
     * <p>之所以不能只按真换行截断：换行进到 trace 日志后可能是**字面量** `\n`
     * （反斜杠 + n，两个字符）。那样“真换行”找不到，就会把紧随其后的
     * 「分区明细(JSON)：[…]」整行吞进路径；而这一行里既有 `"` 又有反斜杠，
     * 会在 Windows 上被当成目录分隔符，最终渲染出
     * `./risk_distribution_map_r0c4.png/n分区明细(JSON)：[{` 这种坏链接。
     * 此时靠 `[` 截断即可（分区明细行必定以 `[` 开头）。
     */
    private static String valueAfter(String text, int from) {
        int end = text.length();
        int marker = text.indexOf(PARTITION_MARKER, from);
        if (marker >= 0 && marker < end) {
            end = marker;
        }
        for (int i = from; i < end; i++) {
            char c = text.charAt(i);
            boolean isStop = false;
            for (char stop : VALUE_STOP_CHARS) {
                if (c == stop) {
                    isStop = true;
                    break;
                }
            }
            if (isStop) {
                end = i;
                break;
            }
        }
        return text.substring(from, end).trim();
    }

    /** 逗号 / 分号分隔的路径串 → 干净的路径列表（去重、保序）。 */
    private static List<String> splitPaths(String payload) {
        List<String> out = new ArrayList<>();
        for (String part : payload.split("\\s*[,;]\\s*")) {
            String path = trimToImageEnd(part);
            if (path != null && !out.contains(path)) {
                out.add(path);
            }
        }
        return out;
    }

    /**
     * 把一段「可能粘了杂字符」的文本收敛成一条图片路径。
     *
     * <p>先去首尾的引号 / 括号 / 空白，再截到**第一个**图片扩展名结尾为止：
     * 干净的绝对路径（含带空格的目录名）原样保留，尾部粘了别的字符则把杂字符切掉。
     */
    private static String trimToImageEnd(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replaceAll("^[\"'\\[(]+|[\\s\"'\\])]+$", "");
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = IMAGE_EXT_PTN.matcher(s);
        return m.find() ? s.substring(0, m.end()) : null;
    }

    /**
     * 获取图表标题
     */
    private String getChartTitle(String tool) {
        if (tool == null) return "图表";
        return switch (tool) {
            case "generate_risk_distribution_map" -> "风险分布图";
            case "generate_scour_heatmap" -> "冲淤热力图";
            case "generate_section_comparison_chart" -> "断面对比图";
            default -> "图表";
        };
    }

    /**
     * 获取相对路径（相对于 Markdown 文件所在目录）。
     *
     * <p>入口处再收敛一次：即便上游给来的路径尾部粘了杂字符，也不会拼出
     * `./xxx.png/n分区明细…` 这种既不是文件、也不是目录的坏链接。
     * 所有报告图片都落在同一个任务目录里，因此 `./<目录名>/<文件名>` 是稳定的形式。
     */
    private String getRelativePath(String absolutePath) {
        String path = trimToImageEnd(absolutePath);
        if (path == null) {
            return new File(String.valueOf(absolutePath)).getName();
        }
        File imageFile = new File(path);
        File parent = imageFile.getParentFile();
        if (parent == null || parent.getName().isEmpty()) {
            return imageFile.getName();
        }
        return "./" + parent.getName() + "/" + imageFile.getName();
    }

    /**
     * 保存 Markdown 文件。
     *
     * <p>文件名里的时间戳**必须**与本次任务的输出目录同源（{@code report_<taskId>_<ts>/}）：
     * 附录里的图片与分区 CSV 都写在该目录下，而导出/删除端点是由报告文件名反解出
     * 目录名的。若这里另取一次 {@code now()}，两秒之差就会让导出找不到目录里的
     * CSV、删除也清不掉图片。取不到目录名时（非工作流调用）才退回当前时间。
     */
    private String saveMarkdownFile(String taskId, String content) {
        try {
            File dir = new File(outputDir, "reports");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String timestamp = timestampOfTaskDir(VisualizationService.getCurrentTaskOutputDir());
            if (timestamp == null) {
                timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            }
            String fileName = String.format("report_%s_%s.md", taskId, timestamp);
            File file = new File(dir, fileName);
            
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }
            
            return file.getAbsolutePath();
        } catch (IOException e) {
            log.error("[markdown] failed to save file", e);
            throw new RuntimeException("Failed to save markdown report", e);
        }
    }

    /** 任务输出目录名（{@code report_<taskId>_<yyyyMMdd_HHmmss>}）→ 时间戳；不匹配返回 null。 */
    static String timestampOfTaskDir(String taskOutputDir) {
        if (taskOutputDir == null || taskOutputDir.isBlank()) {
            return null;
        }
        Matcher m = TASK_DIR_TIMESTAMP_PTN.matcher(new File(taskOutputDir).getName());
        return m.matches() ? m.group(1) : null;
    }
}
