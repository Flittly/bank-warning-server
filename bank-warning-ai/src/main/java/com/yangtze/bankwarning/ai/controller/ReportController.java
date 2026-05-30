package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.ReActAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 报告生成接口（统一使用 ReAct 模式）
 */
@RestController
@RequestMapping("/v0/bank/ai")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private final ReActAgentService reActAgentService;

    public ReportController(ReActAgentService reActAgentService) {
        this.reActAgentService = reActAgentService;
    }

    /**
     * 单断面报告
     */
    @PostMapping("/reports/{section_id}")
    public Map<String, Object> generateReport(@PathVariable("section_id") String sectionId) {
        log.info("[api] report for section={}", sectionId);
        return reActAgentService.generateReActReport(sectionId);
    }

    /**
     * 任务报告
     */
    @PostMapping("/reports/task/{task_id}")
    public Map<String, Object> generateTaskReport(@PathVariable("task_id") String taskId) {
        log.info("[api] report for task={}", taskId);
        return reActAgentService.generateReActTaskReport(taskId);
    }
}
