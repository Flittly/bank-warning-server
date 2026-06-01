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
        log.info("[markdown] generating report for task={}", taskId);

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
            md.append("---\n\n");
            md.append("## 附录：评估图表\n\n");
            
            for (Map<String, String> chart : charts) {
                String tool = chart.get("tool");
                String resultJson = chart.get("result");
                
                // 从 result JSON 中提取文件路径
                String filePath = extractFilePath(resultJson);
                if (filePath != null) {
                    String chartTitle = getChartTitle(tool);
                    md.append("### ").append(chartTitle).append("\n\n");
                    
                    // 使用相对路径引用图片
                    String relativePath = getRelativePath(filePath);
                    md.append("![").append(chartTitle).append("](").append(relativePath).append(")\n\n");
                }
            }
        }
        
        // 保存 Markdown 文件
        String mdFilePath = saveMarkdownFile(taskId, md.toString());
        log.info("[markdown] report saved to: {}", mdFilePath);
        
        return mdFilePath;
    }

    /**
     * 从 JSON 结果中提取文件路径
     */
    private String extractFilePath(String resultJson) {
        if (resultJson == null) return null;
        
        // 简单解析 JSON 获取 file_path
        int start = resultJson.indexOf("\"file_path\":\"");
        if (start == -1) return null;
        start += 13;
        int end = resultJson.indexOf("\"", start);
        if (end == -1) return null;
        
        String path = resultJson.substring(start, end);
        // 处理转义的反斜杠
        return path.replace("\\\\", "\\");
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
            // Markdown 文件在 output/reports/ 目录
            // 图片在 output/ 目录
            // 所以相对路径是 ../filename.png
            File imageFile = new File(absolutePath);
            return "../" + imageFile.getName();
        } catch (Exception e) {
            // 如果无法计算相对路径，返回文件名
            return new File(absolutePath).getName();
        }
    }

    /**
     * 保存 Markdown 文件
     */
    private String saveMarkdownFile(String taskId, String content) {
        try {
            // 确保输出目录存在
            File dir = new File(outputDir, "reports");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            // 生成文件名
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = String.format("report_%s_%s.md", taskId, timestamp);
            File file = new File(dir, fileName);
            
            // 写入文件
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
