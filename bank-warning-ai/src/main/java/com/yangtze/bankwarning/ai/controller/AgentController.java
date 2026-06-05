package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.hook.ReasoningTraceHook;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v0/bank/ai/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final ReActAgent reportAgent;
    private final ReActAgent chatAgent;
    private final ReasoningTraceHook traceHook;

    public AgentController(
            @Qualifier("reportAgent") ReActAgent reportAgent,
            @Qualifier("chatAgent") ReActAgent chatAgent,
            ReasoningTraceHook traceHook) {
        this.reportAgent = reportAgent;
        this.chatAgent = chatAgent;
        this.traceHook = traceHook;
    }

    @PostMapping("/report/{section_id}")
    public Map<String, Object> generateReport(@PathVariable("section_id") String sectionId) {
        log.info("[api] agent report for section={}", sectionId);
        traceHook.clearLog();
        Msg result = reportAgent.call(Msg.builder()
                .textContent("请对断面 " + sectionId + " 进行风险评估报告生成").build()).block();
        return Map.of("success", true, "data", result.getTextContent());
    }

    @PostMapping("/report/task/{task_id}")
    public Map<String, Object> generateTaskReport(@PathVariable("task_id") String taskId) {
        log.info("[api] agent report for task={}", taskId);
        traceHook.clearLog();
        Msg result = reportAgent.call(Msg.builder()
                .textContent("请对任务 " + taskId + " 进行风险评估报告生成").build()).block();
        return Map.of("success", true, "data", result.getTextContent());
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        log.info("[api] unified chat question={}", question);
        traceHook.clearLog();
        Msg result = chatAgent.call(Msg.builder().textContent(question).build()).block();
        return Map.of("success", true, "data", result.getTextContent());
    }

    @GetMapping("/thoughts")
    public List<ReasoningTraceHook.ThoughtLogEntry> getThoughts() {
        return traceHook.getLog();
    }

    @DeleteMapping("/thoughts")
    public Map<String, Object> clearThoughts() {
        traceHook.clearLog();
        return Map.of("success", true);
    }
}
