package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.ModelService;
import com.yangtze.bankwarning.ai.workflow.PlanProgress;
import com.yangtze.bankwarning.ai.workflow.ReportWorkflowService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v0/bank/ai/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final HarnessAgent defaultChatAgent;
    private final ModelService modelService;
    private final ReportWorkflowService reportWorkflow;
    private final ReasoningTraceMiddleware traceMiddleware;
    private final KnowledgeService knowledgeService;

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String outputDir;

    public AgentController(
            @Qualifier("chatAgent") HarnessAgent defaultChatAgent,
            ModelService modelService,
            ReportWorkflowService reportWorkflow,
            KnowledgeService knowledgeService,
            @Qualifier("chatTraceMiddleware") ReasoningTraceMiddleware traceMiddleware) {
        this.defaultChatAgent = defaultChatAgent;
        this.modelService = modelService;
        this.reportWorkflow = reportWorkflow;
        this.traceMiddleware = traceMiddleware;
        this.knowledgeService = knowledgeService;
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
            result.put("filename", reportWorkflow.getReportFileName(taskId));
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

    @SuppressWarnings("unchecked")
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        String question = (String) body.get("question");
        String sessionId = (String) body.get("sessionId");
        String modelKey = (String) body.getOrDefault("model", "");
        List<String> skills = (List<String>) body.get("skills");
        List<String> reportIds = (List<String>) body.get("reportIds");
        HarnessAgent agent = modelService.getOrCreateAgent(modelKey, defaultChatAgent);
        if (agent == null) agent = defaultChatAgent;
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .build();

        StringBuilder prompt = new StringBuilder();

        // 预检索：自动查知识库，将相关知识拼入 prompt（不等 agent 调用 query_knowledge）
        try {
            RetrieveConfig config = RetrieveConfig.builder()
                    .limit(3)
                    .scoreThreshold(0.4)
                    .build();
            List<Document> docs = knowledgeService.retrieve(question, config).block();
            if (docs != null && !docs.isEmpty()) {
                prompt.append("以下是与用户问题相关的专业知识（系统自动检索，已为你准备好）：\n");
                for (Document doc : docs) {
                    prompt.append("- ").append(doc.getMetadata().getContent()).append("\n");
                }
                prompt.append("\n");
            }
        } catch (Exception e) {
            log.warn("[chat] 预检索失败，跳过: {}", e.getMessage());
        }

        boolean hasReports = reportIds != null && !reportIds.isEmpty();
        // 拼入拖入的报告内容作为上下文
        if (hasReports) {
            prompt.append("以下是用户拖入的已有报告，请基于这些报告内容回答用户问题：\n\n");
            File reportsDir = new File(outputDir, "reports");
            for (String filename : reportIds) {
                File f = new File(reportsDir, filename);
                if (f.exists()) {
                    try {
                        String content = Files.readString(f.toPath());
                        prompt.append("--- 报告：").append(filename).append(" ---\n");
                        prompt.append(content).append("\n");
                    } catch (IOException e) {
                        log.warn("[chat] 无法读取报告文件: {}", filename, e);
                    }
                }
            }
            prompt.append("\n--- 以上为已有报告内容 ---\n\n");
        }
        // 拼入启用的技能
        if (skills != null && !skills.isEmpty()) {
            prompt.append("以下是用户本次对话中启用的技能，请优先使用这些技能来回答问题：\n- ");
            prompt.append(String.join("\n- ", skills));
            prompt.append("\n\n");
        }
        // 如果有报告，要求 AI 用分隔符区分"总结"和"完整修改后报告"
        if (hasReports) {
            prompt.append("重要指令：如果用户要求你修改报告内容，请你先在回复的开头用 1-2 句话简要说明你做了什么修改，")
                  .append("然后在单独一行写上分隔标记 <!--REPORT-->，之后输出完整的修改后报告全文。")
                  .append("如果用户不是要修改报告而只是问问题，则正常回答即可，不需要写 <!--REPORT-->。\n\n");
        }
        prompt.append("用户问题：").append(question);

        Msg response = agent.call(
                List.of(new UserMessage("user", prompt.toString())),
                ctx
        ).block();
        String rawText = extractText(response);

        // 如果 AI 返回了报告修改标记，解析并写回文件
        String separator = "<!--REPORT-->";
        int sepIdx = rawText.indexOf(separator);
        String chatReply;
        List<Map<String, String>> updatedReports = new ArrayList<>();
        if (hasReports && sepIdx >= 0) {
            chatReply = rawText.substring(0, sepIdx).trim();
            String modifiedReport = rawText.substring(sepIdx + separator.length()).trim();
            if (!modifiedReport.isEmpty()) {
                File reportsDir = new File(outputDir, "reports");
                for (String filename : reportIds) {
                    File f = new File(reportsDir, filename);
                    try {
                        Files.writeString(f.toPath(), modifiedReport);
                        log.info("[chat] 报告已更新: {}", filename);
                        updatedReports.add(Map.of("filename", filename, "updated", "true"));
                    } catch (IOException e) {
                        log.error("[chat] 写入报告失败: {}", filename, e);
                        updatedReports.add(Map.of("filename", filename, "error", e.getMessage()));
                    }
                }
            }
        } else {
            chatReply = rawText;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", chatReply);
        if (!updatedReports.isEmpty()) {
            result.put("updatedReports", updatedReports);
        }
        return result;
    }

    private String extractText(Msg msg) {
        if (msg == null) return "";
        String text = msg.getTextContent();
        if (text != null && !text.isBlank()) return text;
        List<ThinkingBlock> thoughts = msg.getContentBlocks(ThinkingBlock.class);
        if (thoughts != null && !thoughts.isEmpty()) {
            return thoughts.stream()
                    .map(ThinkingBlock::getThinking)
                    .reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b);
        }
        return msg.getContent() != null ? msg.getContent().toString() : "";
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
