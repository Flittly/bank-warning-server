package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.ReActAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 报告生成接口
 * 统一使用 ReAct 多轮推理模式
 */
@RestController
@RequestMapping("/v0/bank/ai/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final ReActAgentService reActAgentService;

    public AgentController(ReActAgentService reActAgentService) {
        this.reActAgentService = reActAgentService;
    }

    /**
     * 单断面报告
     */
    @PostMapping("/report/{section_id}")
    public Map<String, Object> generateReport(@PathVariable("section_id") String sectionId) {
        log.info("[api] agent report for section={}", sectionId);
        return reActAgentService.generateReActReport(sectionId);
    }

    /**
     * 任务报告
     */
    @PostMapping("/report/task/{task_id}")
    public Map<String, Object> generateTaskReport(@PathVariable("task_id") String taskId) {
        log.info("[api] agent report for task={}", taskId);
        return reActAgentService.generateReActTaskReport(taskId);
    }
}
