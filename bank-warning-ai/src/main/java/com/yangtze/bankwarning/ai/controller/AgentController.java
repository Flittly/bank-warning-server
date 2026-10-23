package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.security.SkillPathGuard;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.ModelService;
import com.yangtze.bankwarning.ai.workflow.PlanProgress;
import com.yangtze.bankwarning.ai.workflow.ReportWorkflowService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.rag.model.Document;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper; // 注入 AgentScopeConfig 里那个（Jackson 2）

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String outputDir;

    public AgentController(
            @Qualifier("chatAgent") HarnessAgent defaultChatAgent,
            ModelService modelService,
            ReportWorkflowService reportWorkflow,
            KnowledgeService knowledgeService,
            @Qualifier("chatTraceMiddleware") ReasoningTraceMiddleware traceMiddleware,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.defaultChatAgent = defaultChatAgent;
        this.modelService = modelService;
        this.reportWorkflow = reportWorkflow;
        this.traceMiddleware = traceMiddleware;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成任务风险评估报告。
     *
     * <p>请求体（可选）：{@code {"splitRows":2,"splitCols":2,"splitTiles":"[{...}]"}}。
     * 分幅语义、校验与出图接口 {@code /viz/risk-map/batch} **完全一致**（复用
     * {@code VisualizationService.validateSplitConfig}）：三段全空 = 不分幅，
     * 报告结构与历史版本一致。
     *
     * <p>非法的分幅参数在起工作流之前就返回 error，不会白跑 Step 1。
     */
    @PostMapping("/report/task/{task_id}")
    public Map<String, Object> generateTaskReport(
            @PathVariable("task_id") String taskId,
            @RequestBody(required = false) Map<String, Object> body) {
        Integer splitRows;
        Integer splitCols;
        try {
            splitRows = SplitParams.asInteger(body == null ? null : body.get("splitRows"));
            splitCols = SplitParams.asInteger(body == null ? null : body.get("splitCols"));
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "taskId", taskId, "error", e.getMessage());
        }
        String splitTiles = SplitParams.asString(body == null ? null : body.get("splitTiles"));
        log.info("[api] workflow report for task={}, split={}x{}, tiles={}",
                taskId, splitRows, splitCols,
                splitTiles == null || splitTiles.isBlank() ? "无" : "自定义");
        try {
            String report = reportWorkflow.executeTaskReport(taskId, splitRows, splitCols, splitTiles);
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
            // 进度只是「附加信息」：它自身再失败也不能把已捕获的真实错误顶掉，
            // 否则会被 Spring 转成无 JSON 的 500，前端只能看到解析失败，
            // 真正的报错原因（本次即 pgjdbc 的 SQL 异常）就被掩盖了。
            try {
                result.put("progress", reportWorkflow.getProgress(taskId));
            } catch (Exception progressError) {
                log.warn("[api] 附带进度查询失败（忽略）: {}", progressError.getMessage());
            }
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

    /**
     * 一次性对话接口（非流式）。
     *
     * <p>保留它是为了向后兼容（报告工作流等同步调用方）。过程不可见：{@code call().block()}
     * 只在整轮 ReAct 结束后才 emit 唯一一个 {@link Msg}，期间 HTTP 上没有任何字节。
     * 前端对话请用 {@link #chatStream}。
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        ChatRequest req = prepare(body);
        Msg response = req.agent()
                .call(List.of(new UserMessage("user", req.prompt())), req.ctx())
                .block();
        String rawText = extractText(response);

        List<Map<String, String>> updatedReports = new ArrayList<>();
        String chatReply = rewriteReports(rawText, req.reportFiles(), req.hasReports(), updatedReports);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", chatReply);
        if (!updatedReports.isEmpty()) {
            result.put("updatedReports", updatedReports);
        }
        if (!req.rejectedReports().isEmpty()) {
            result.put("rejectedReports", req.rejectedReports());
        }
        return result;
    }

    /**
     * 组装一次对话所需的全部前置状态：Agent、RuntimeContext、完整 prompt、报告文件清单。
     *
     * <p>流式与非流式**共用这一份**：两条链路只有"怎么消费 agent 输出"不同，
     * 喂进去的东西必须一模一样，否则同一个问题在两条链路上会得到不同答案。
     */
    @SuppressWarnings("unchecked")
    private ChatRequest prepare(Map<String, Object> body) {
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
            List<Document> docs = knowledgeService.retrieve(question, 3, 0.4).block();
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

        // 把请求体传入的 reportIds 解析为 reports 目录内的真实文件。
        // 只接受纯文件名（不含路径分隔符 / 盘符），越界项丢弃并记入 rejectedReports；
        // 读写两个方向共用这一份 File 列表，杜绝 ../ 逃逸出 reports 目录。
        File reportsDir = new File(outputDir, "reports");
        List<File> reportFiles = new ArrayList<>();
        List<String> rejectedReports = new ArrayList<>();
        if (reportIds != null) {
            for (String reportId : reportIds) {
                try {
                    reportFiles.add(resolveReportFile(reportsDir, reportId));
                } catch (IllegalArgumentException e) {
                    log.warn("[chat] 拒绝非法报告文件名: {} ({})", reportId, e.getMessage());
                    rejectedReports.add(String.valueOf(reportId));
                }
            }
        }
        boolean hasReports = !reportFiles.isEmpty();
        // 拼入拖入的报告内容作为上下文
        if (hasReports) {
            prompt.append("以下是用户拖入的已有报告，请基于这些报告内容回答用户问题：\n\n");
            for (File f : reportFiles) {
                if (f.exists()) {
                    try {
                        String content = Files.readString(f.toPath());
                        prompt.append("--- 报告：").append(f.getName()).append(" ---\n");
                        prompt.append(content).append("\n");
                    } catch (IOException e) {
                        log.warn("[chat] 无法读取报告文件: {}", f.getName(), e);
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

        return new ChatRequest(agent, ctx, sessionId, prompt.toString(),
                reportFiles, rejectedReports, hasReports);
    }

    /**
     * 流式对话接口（SSE）。
     *
     * <p>与 {@link #chat} 的唯一差别是「怎么消费 agent 的输出」：这里订阅
     * {@code streamEvents()}，把 ReAct 循环内部的过程事件逐条写成 SSE 帧，
     * 前端看到的是「思考 → 调工具 → 工具结果 → 成文」的时间线，而不是等整段返回后一次性出现。
     *
     * <p>帧协议（event 名 → data）：
     * <pre>
     *   start            {sessionId}
     *   round            {round}                      第 N 轮模型调用开始
     *   thinking         {delta}                      思考增量
     *   text             {delta}                      正文增量
     *   tool_call        {id,name}                    工具开始调用（入参还没生成完）
     *   tool_args        {id,name,delta}              入参 JSON 增量
     *   tool_call_end    {id,name,args}               入参生成完毕（完整 JSON 串）
     *   tool_result      {id,name,delta}              工具结果增量
     *   tool_result_end  {id,name,state}              工具执行结束
     *   warn             {message}                    达到 maxIters 等告警
     *   error            {message}                    整条流失败
     *   done             {success,data,thinking,updatedReports,rejectedReports}
     * </pre>
     *
     * <p>注意：{@code text} 帧只用于**显示**。真正该存什么由 {@code done.data} 决定 ——
     * 报告改写（{@code <!--REPORT-->} 分隔符）必须等整段结束才能做。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        // 0 = 永不超时：LLM + 多轮工具可能跑几分钟，默认 30s 会从中间掐断
        SseEmitter emitter = new SseEmitter(0L);
        // 反向代理默认会攒包，必须显式关掉，否则前端仍是一次性收到
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        ChatRequest req;
        try {
            req = prepare(body);
        } catch (Exception e) {
            log.error("[chat-stream] 准备阶段失败: {}", e.getMessage(), e);
            sendSse(emitter, "error", payload("message", String.valueOf(e.getMessage())));
            emitter.complete();
            return emitter;
        }

        ChatStreamState st = new ChatStreamState();
        sendSse(emitter, "start", payload("sessionId", req.sessionId()));

        Flux<AgentEvent> events = req.agent().streamEvents(
                List.of(new UserMessage("user", req.prompt())), req.ctx());
        Disposable sub = events.subscribe(
                ev -> onStreamEvent(ev, emitter, st),
                err -> {
                    log.error("[chat-stream] 流式失败: {}", err.getMessage(), err);
                    st.error = String.valueOf(err.getMessage());
                    sendSse(emitter, "error", payload("message", st.error));
                    emitter.complete();
                },
                () -> {
                    finishStream(req, emitter, st);
                    emitter.complete();
                });
        // 客户端断开 / 超时 → 取消订阅，让 agent 那一路停下来，别白烧 token
        emitter.onCompletion(sub::dispose);
        emitter.onTimeout(() -> {
            sub.dispose();
            emitter.complete();
        });
        emitter.onError(e -> sub.dispose());
        return emitter;
    }

    /** 把 2.0.4 的 32 类 AgentEvent 收敛成前端需要的几帧；其余事件（*BlockStart/*BlockEnd 等）不推。 */
    private void onStreamEvent(AgentEvent event, SseEmitter emitter, ChatStreamState st) {
        if (event instanceof TextBlockDeltaEvent e) {
            st.text.append(e.getDelta());
            if (!sendSse(emitter, "text", payload("delta", e.getDelta()))) throw new StreamAborted();
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            st.thinking.append(e.getDelta());
            if (!sendSse(emitter, "thinking", payload("delta", e.getDelta()))) throw new StreamAborted();
        } else if (event instanceof ToolCallStartEvent e) {
            if (!sendSse(emitter, "tool_call", payload("id", e.getToolCallId(), "name", e.getToolCallName())))
                throw new StreamAborted();
        } else if (event instanceof ToolCallDeltaEvent e) {
            st.toolArgs.computeIfAbsent(e.getToolCallId(), k -> new StringBuilder()).append(e.getDelta());
            if (!sendSse(emitter, "tool_args", payload("id", e.getToolCallId(),
                    "name", e.getToolCallName(), "delta", e.getDelta()))) throw new StreamAborted();
        } else if (event instanceof ToolCallEndEvent e) {
            StringBuilder args = st.toolArgs.get(e.getToolCallId());
            if (!sendSse(emitter, "tool_call_end", payload("id", e.getToolCallId(),
                    "name", e.getToolCallName(), "args", args == null ? "" : args.toString())))
                throw new StreamAborted();
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            st.toolResults.computeIfAbsent(e.getToolCallId(), k -> new StringBuilder()).append(e.getDelta());
            if (!sendSse(emitter, "tool_result", payload("id", e.getToolCallId(),
                    "name", e.getToolCallName(), "delta", e.getDelta()))) throw new StreamAborted();
        } else if (event instanceof ToolResultEndEvent e) {
            st.toolResults.remove(e.getToolCallId());
            if (!sendSse(emitter, "tool_result_end", payload("id", e.getToolCallId(),
                    "name", e.getToolCallName(), "state", e.getState() == null ? "" : e.getState().name())))
                throw new StreamAborted();
        } else if (event instanceof ModelCallStartEvent) {
            st.round++;
            if (!sendSse(emitter, "round", payload("round", st.round))) throw new StreamAborted();
        } else if (event instanceof ExceedMaxItersEvent e) {
            sendSse(emitter, "warn", payload("message",
                    "已达到最大推理轮数 " + e.getMaxIters() + "（当前 " + e.getCurrentIter() + "），回答可能不完整"));
        } else if (event instanceof AgentResultEvent e) {
            // 兜底：某些模型不发 text delta，最终文本只能从这里取
            st.result = e.getResult();
        }
    }

    /**
     * 流结束后的收口：正文取「增量拼接」，拼不出来再退回 final Msg；
     * 然后跑与同步接口**完全相同**的报告改写逻辑，最后发 {@code done} 帧。
     */
    private void finishStream(ChatRequest req, SseEmitter emitter, ChatStreamState st) {
        String rawText = st.text.length() > 0 ? st.text.toString() : extractText(st.result);
        List<Map<String, String>> updatedReports = new ArrayList<>();
        String chatReply = rewriteReports(rawText, req.reportFiles(), req.hasReports(), updatedReports);

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("success", true);
        done.put("data", chatReply);
        done.put("thinking", st.thinking.toString());
        if (!updatedReports.isEmpty()) done.put("updatedReports", updatedReports);
        if (!req.rejectedReports().isEmpty()) done.put("rejectedReports", req.rejectedReports());
        if (sendSse(emitter, "done", done)) {
            log.info("[chat-stream] 完成: 正文 {} 字 / 思考 {} 字 / {} 轮",
                    chatReply.length(), st.thinking.length(), st.round);
        }
    }

    /**
     * 解析 AI 回复里的 {@code <!--REPORT-->} 分隔符并回写报告文件。
     *
     * <p>流式与非流式共用；返回的是「应该展示/存储的正文」，
     * 改写结果通过 {@code updatedReports} 带出。
     */
    private String rewriteReports(String rawText, List<File> reportFiles,
                                  boolean hasReports, List<Map<String, String>> updatedReports) {
        String separator = "<!--REPORT-->";
        int sepIdx = rawText.indexOf(separator);
        if (!hasReports || sepIdx < 0) {
            return rawText;
        }
        String reply = rawText.substring(0, sepIdx).trim();
        String modifiedReport = rawText.substring(sepIdx + separator.length()).trim();
        if (modifiedReport.isEmpty()) {
            return reply;
        }
        for (File f : reportFiles) {
            try {
                Files.writeString(f.toPath(), modifiedReport);
                log.info("[chat] 报告已更新: {}", f.getName());
                updatedReports.add(Map.of("filename", f.getName(), "updated", "true"));
            } catch (IOException e) {
                log.error("[chat] 写入报告失败: {}", f.getName(), e);
                updatedReports.add(Map.of("filename", f.getName(), "error", String.valueOf(e.getMessage())));
            }
        }
        return reply;
    }

    /** 写一帧；返回 false 表示客户端已断开（不要抛 checked 异常，避免污染 reactor 的错误通道）。 */
    private boolean sendSse(SseEmitter emitter, String name, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(json(payload)));
            return true;
        } catch (Exception e) {
            log.debug("[chat-stream] 写帧失败（客户端可能已断开）: {} {}", name, e.getMessage());
            return false;
        }
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("[chat-stream] JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    /** 按 k1,v1,k2,v2… 组 payload；值为 null 时落成空串（Map.of 不允许 null）。 */
    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return m;
    }

    /** 一次对话的完整前置状态（流式/非流式共用）。 */
    private record ChatRequest(HarnessAgent agent, RuntimeContext ctx, String sessionId,
                               String prompt, List<File> reportFiles,
                               List<String> rejectedReports, boolean hasReports) {
    }

    /** 流式过程中的累计态（跨事件可变，故用普通类而非 record）。 */
    private static final class ChatStreamState {
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final Map<String, StringBuilder> toolArgs = new LinkedHashMap<>();
        final Map<String, StringBuilder> toolResults = new LinkedHashMap<>();
        Msg result;
        int round;
        String error;
    }

    /** 客户端断开：用来从 onNext 里跳出、触发取消订阅。 */
    private static final class StreamAborted extends RuntimeException {
        StreamAborted() {
            super("SSE client disconnected", null, false, false);
        }
    }

    /**
     * 把请求体传入的 reportId 解析为 reportsDir 下的真实文件。
     *
     * 安全约束：只接受"单个文件名"——任何路径分隔符（/ \）、盘符冒号、
     * 或以 . / .. 结尾的形式一律拒绝；再经 {@link SkillPathGuard#safeResolve}
     * 做 normalize 后的目录包含校验兜底，确保结果必然落在 reportsDir 内。
     *
     * @throws IllegalArgumentException 文件名为空或试图逃逸出 reportsDir
     */
    private File resolveReportFile(File reportsDir, String reportId) {
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("报告文件名不能为空");
        }
        String name = reportId.strip();
        boolean hasSeparator = name.indexOf('/') >= 0 || name.indexOf('\\') >= 0;
        // Windows 盘符相对路径（如 C:foo）带 root 但不是绝对路径，resolve 时会被特殊处理，一并挡掉
        boolean hasDriveColon = name.indexOf(':') >= 0;
        if (hasSeparator || hasDriveColon || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("报告文件名不允许包含路径分隔符或盘符: " + name);
        }
        Path base = reportsDir.toPath().toAbsolutePath().normalize();
        return SkillPathGuard.safeResolve(base, name).toFile();
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
