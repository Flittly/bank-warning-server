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
            description = "使用 skills/pdf/ 下的 Python 脚本处理 PDF 文件，支持提取文本、提取表格、合并拆分 PDF 等操作。先调用 load_skill_through_path 加载 pdf skill 了解可用脚本。")
    public String processPdf(
            @ToolParam(name = "scriptName",
                    description = "要执行的脚本文件名，例如 extract_text.py、extract_tables.py") String scriptName,
            @ToolParam(name = "filePath",
                    description = "PDF 文件的绝对路径") String filePath) {
        log.info("[tool] process_pdf, script={}, file={}", scriptName, filePath);
        var result = pdfService.processPdf(scriptName, filePath);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return (String) result.get("content");
        }
        return "处理失败: " + result.get("error");
    }
}