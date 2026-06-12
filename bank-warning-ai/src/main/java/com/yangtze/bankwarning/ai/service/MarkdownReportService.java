package com.yangtze.bankwarning.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Markdown 报告生成服务
 * 将报告文字和图表整合到一个 Markdown 文件中
 */
@Service
public class MarkdownReportService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownReportService.class);

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String outputDir;

    /**
     * 生成包含图表的 Markdown 报告
     */
    public String generateMarkdownReport(String taskId, String reportText, List<Map<String, String>> charts) {
        String content = buildMarkdownContent(taskId, reportText, charts);
        String mdFilePath = saveMarkdownFile(taskId, content);
        log.info("[markdown] report saved to: {}", mdFilePath);
        return mdFilePath;
    }

    public String buildMarkdownContent(String taskId, String reportText, List<Map<String, String>> charts) {
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

        return md.toString();
    }

    private void appendChartsAppendix(StringBuilder md, List<Map<String, String>> charts) {
        md.append("---\n\n");
        md.append("## 附录：评估图表\n\n");
        for (Map<String, String> chart : charts) {
            String tool = chart.get("tool");
            String resultJson = chart.get("result");
            String filePath = extractFilePath(resultJson);
            if (filePath != null) {
                String chartTitle = getChartTitle(tool);
                md.append("### ").append(chartTitle).append("\n\n");
                String relativePath = getRelativePath(filePath);
                md.append("![").append(chartTitle).append("](").append(relativePath).append(")\n\n");
            }
        }
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

    /**
     * 从工具返回中提取文件路径。支持三种格式：
     *  1. JSON: {"file_path":"xxx.png"}
     *  2. 纯文本: 图表生成成功，文件路径：xxx.png  (VisualizationTools.formatResult 的实际输出)
     *  3. 行内 .png 路径
     */
    public static String extractFilePath(String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) return null;

        // 1) JSON 格式
        int start = resultJson.indexOf("\"file_path\":\"");
        if (start != -1) {
            start += 13;
            int end = resultJson.indexOf("\"", start);
            if (end != -1) {
                return resultJson.substring(start, end).replace("\\\\", "\\");
            }
        }

        // 2) 纯文本 "文件路径：xxx"
        int idx = resultJson.indexOf("文件路径：");
        if (idx != -1) {
            String tail = resultJson.substring(idx + "文件路径：".length()).trim();
            int end = tail.indexOf('\n');
            String path = (end == -1 ? tail : tail.substring(0, end)).trim();
            if (!path.isEmpty()) return path;
        }

        // 3) 行内 .png 路径兜底
        int png = resultJson.indexOf(".png");
        if (png != -1) {
            int s = Math.max(0, Math.max(resultJson.lastIndexOf(' ', png), resultJson.lastIndexOf('"', png)) + 1);
            return resultJson.substring(s, png + 4);
        }

        return null;
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
     * 获取相对路径（相对于 Markdown 文件所在目录）
     */
    private String getRelativePath(String absolutePath) {
        try {
            File imageFile = new File(absolutePath);
            String folder = imageFile.getParentFile().getName();
            return "./" + folder + "/" + imageFile.getName();
        } catch (Exception e) {
            return new File(absolutePath).getName();
        }
    }

    /**
     * 保存 Markdown 文件
     */
    private String saveMarkdownFile(String taskId, String content) {
        try {
            File dir = new File(outputDir, "reports");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
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
}
