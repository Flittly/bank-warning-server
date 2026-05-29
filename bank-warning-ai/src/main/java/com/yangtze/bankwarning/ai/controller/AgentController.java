package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.AgentReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 驱动的报告生成接口
 * LLM 自主决定是否生成图表
 */
@RestController
@RequestMapping("/v0/bank/ai")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final AgentReportService agentReportService;

    public AgentController(AgentReportService agentReportService) {
        this.agentReportService = agentReportService;
    }

    /**
     * Agent 驱动的单断面报告生成
     * LLM 自主决定是否需要生成图表
     */
    @PostMapping("/agent/report/{section_id}")
    public Map<String, Object> generateAgentReport(@PathVariable("section_id") String sectionId) {
        log.info("[api] agent report for section={}", sectionId);
        return agentReportService.generateAgentReport(sectionId);
    }

    /**
     * Agent 驱动的任务报告生成
     * LLM 自主决定生成哪些图表
     */
    @PostMapping("/agent/report/task/{task_id}")
    public Map<String, Object> generateAgentTaskReport(@PathVariable("task_id") String taskId) {
        log.info("[api] agent report for task={}", taskId);
        return agentReportService.generateAgentTaskReport(taskId);
    }
}
