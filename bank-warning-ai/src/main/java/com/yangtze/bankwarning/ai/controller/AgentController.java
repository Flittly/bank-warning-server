package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.workflow.PlanProgress;
import com.yangtze.bankwarning.ai.workflow.ReportWorkflowService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v0/bank/ai/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final ReActAgent chatAgent;
    private final ReportWorkflowService reportWorkflow;
    private final ReasoningTraceMiddleware traceMiddleware;

    public AgentController(
            @Qualifier("chatAgent") ReActAgent chatAgent,
            ReportWorkflowService reportWorkflow,
            ReasoningTraceMiddleware traceMiddleware) {
        this.chatAgent = chatAgent;
        this.reportWorkflow = reportWorkflow;
        this.traceMiddleware = traceMiddleware;
    }

    @PostMapping("/report/task/{task_id}")
    public Map<String, Object> generateTaskReport(@PathVariable("task_id") String taskId) {
        log.info("[api] workflow report for task={}", taskId);
        try {
            String report = reportWorkflow.executeTaskReport(taskId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("taskId", taskId);
            result.put("data", report);
            result.put("progress", reportWorkflow.getProgress(taskId));
            return result;
        } catch (Exception e) {
            log.error("[api] workflow report failed: {}", e.getMessage(), e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("taskId", taskId);
            result.put("error", e.getMessage());
            result.put("progress", reportWorkflow.getProgress(taskId));
            return result;
        }
    }

    @GetMapping("/report/progress")
    public Map<String, Object> getReportProgress(@RequestParam("taskId") String taskId) {
        PlanProgress progress = reportWorkflow.getProgress(taskId);
        Map<String, Object> result = new HashMap<>();
        if (progress == null) {
            result.put("success", false);
            result.put("taskId", taskId);
            result.put("error", "未找到该任务的工作流状态（可能尚未启动）");
            return result;
        }
        result.put("success", true);
        result.put("data", progress);
        return result;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        log.info("[api] unified chat question={}", question);
        traceMiddleware.clearLog();
        Msg result = chatAgent.call(List.of(new UserMessage("user", question))).block();
        return Map.of("success", true, "data", result.getTextContent());
    }

    @GetMapping("/thoughts")
    public List<ReasoningTraceMiddleware.ThoughtLogEntry> getThoughts() {
        return traceMiddleware.getLog();
    }

    @DeleteMapping("/thoughts")
    public Map<String, Object> clearThoughts() {
        traceMiddleware.clearLog();
        return Map.of("success", true);
    }
}
