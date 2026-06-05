package com.yangtze.bankwarning.ai.tool;

import com.yangtze.bankwarning.ai.service.PdfService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PdfTools {

    private static final Logger log = LoggerFactory.getLogger(PdfTools.class);
    private final PdfService pdfService;

    public PdfTools(PdfService pdfService) {
        this.pdfService = pdfService;
    }

    @Tool(name = "process_pdf",
            description = "从 Nacos skill 仓库加载对应 skill 的 scripts 目录下的 Python 脚本处理 PDF 文件。"
                    + "skillName 必填，脚本会从 .skills-cache/<skillName>/scripts/ 下查找。"
                    + "支持提取文本、提取表格、合并拆分 PDF 等操作。")
    public String processPdf(
            @ToolParam(name = "skillName",
                    description = "Skill 名称，例如 pdf、weather 等，必须与 Nacos/classpath 中已注册的 skill 名一致") String skillName,
            @ToolParam(name = "scriptName",
                    description = "要执行的脚本文件名，例如 extract_text.py、extract_tables.py") String scriptName,
            @ToolParam(name = "filePath",
                    description = "PDF 文件的绝对路径") String filePath) {
        log.info("[tool] process_pdf, skill={}, script={}, file={}", skillName, scriptName, filePath);
        var result = pdfService.processPdf(skillName, scriptName, filePath);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return (String) result.get("content");
        }
        return "处理失败: " + result.get("error");
    }
}