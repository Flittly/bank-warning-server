package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.prompt.Prompts;
import com.yangtze.bankwarning.ai.tool.VisualizationToolExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 范式的 Agent 报告生成服务
 * 多轮推理：Thought → Action → Observation → ... → Final Answer
 */
@Service
public class ReActAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentService.class);
    private static final int MAX_ITERATIONS = 10;  // 最大循环次数，防止无限循环
    
    private final LlmClient llmClient;
    private final Prompts prompts;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final VisualizationToolExecutors toolExecutors;
    private final MarkdownReportService markdownReportService;

    public ReActAgentService(LlmClient llmClient, Prompts prompts,
                              JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              VisualizationToolExecutors toolExecutors,
                              MarkdownReportService markdownReportService) {
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.toolExecutors = toolExecutors;
        this.markdownReportService = markdownReportService;
    }

    /**
     * ReAct 驱动的报告生成
     */
    public Map<String, Object> generateReActReport(String sectionId) {
        log.info("[react] generating for section={}", sectionId);

        // 1. 查询数据
        Map<String, Object> sectionData = querySectionData(sectionId);
        if (sectionData.isEmpty()) {
            return Map.of("success", false, "error", "未找到断面数据");
        }

        // 2. 构建初始消息
        String systemPrompt = getReActSystemPrompt();
        String userMessage = buildUserMessage(sectionData);
        
        List<String> messages = new ArrayList<>();
        messages.add("System: " + systemPrompt);
        messages.add("User: " + userMessage);

        // 3. ReAct 循环
        List<Map<String, String>> thoughtLog = new ArrayList<>();
        List<Map<String, String>> toolCalls = new ArrayList<>();
        
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("[react] iteration {}", i + 1);
            
            // 调用 LLM
            String response = llmClient.chat(systemPrompt, String.join("\n\n", messages));
            log.info("[react] raw response:\n{}", response);
            
            // 解析响应
            ReActResponse parsed = parseResponse(response);
            
            // 记录思考过程
            if (parsed.thought != null) {
                thoughtLog.add(Map.of("iteration", String.valueOf(i + 1), "thought", parsed.thought));
                log.info("[react] Thought: {}", parsed.thought);
            }
            
            // 如果有 Final Answer，结束循环
            if (parsed.finalAnswer != null) {
                log.info("[react] completed after {} iterations", i + 1);
                return buildResult(sectionId, parsed.finalAnswer, thoughtLog, toolCalls);
            }
            
            // 如果有 Action，执行工具
            if (parsed.action != null) {
                log.info("[react] Action: {}", parsed.action);
                
                String toolResult = executeTool(parsed.action, parsed.actionInput);
                toolCalls.add(Map.of(
                    "iteration", String.valueOf(i + 1),
                    "tool", parsed.action,
                    "input", parsed.actionInput != null ? parsed.actionInput : "",
                    "result", toolResult.length() > 200 ? toolResult.substring(0, 200) + "..." : toolResult
                ));
                
                // 将工具结果添加到消息中
                messages.add("Observation: " + toolResult);
            } else if (parsed.thought != null) {
                // 只有 Thought 没有 Action，提示 LLM 继续
                log.info("[react] Thought without Action, prompting to continue");
                messages.add("Assistant: " + response);
                messages.add("User: 请继续执行下一步。你必须输出 Action 和 Action Input，或者 Final Answer。");
            } else {
                // 无法解析，将原始响应加入消息
                messages.add("Assistant: " + response);
                messages.add("User: 请按照格式输出：Thought: ... Action: ... Action Input: ...");
            }
        }
        
        // 超过最大迭代次数
        log.warn("[react] reached max iterations {}", MAX_ITERATIONS);
        return buildResult(sectionId, "报告生成超时，请重试", thoughtLog, toolCalls);
    }

    /**
     * ReAct 驱动的任务报告生成
     */
    public Map<String, Object> generateReActTaskReport(String taskId) {
        log.info("[react] generating for task={}", taskId);

        // 1. 查询任务数据
        List<Map<String, Object>> taskData = queryTaskData(taskId);
        if (taskData.isEmpty()) {
            return Map.of("success", false, "error", "未找到任务数据");
        }

        // 2. 构建初始消息
        String systemPrompt = getReActSystemPrompt();
        String userMessage = buildTaskUserMessage(taskId, taskData);
        
        List<String> messages = new ArrayList<>();
        messages.add("System: " + systemPrompt);
        messages.add("User: " + userMessage);

        // 3. ReAct 循环
        List<Map<String, String>> thoughtLog = new ArrayList<>();
        List<Map<String, String>> toolCalls = new ArrayList<>();
        
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("[react] iteration {}", i + 1);
            
            String response = llmClient.chat(systemPrompt, String.join("\n\n", messages));
            log.info("[react] raw response:\n{}", response);
            ReActResponse parsed = parseResponse(response);
            
            if (parsed.thought != null) {
                thoughtLog.add(Map.of("iteration", String.valueOf(i + 1), "thought", parsed.thought));
                log.info("[react] Thought: {}", parsed.thought);
            }
            
            if (parsed.finalAnswer != null) {
                log.info("[react] completed after {} iterations", i + 1);
                return buildTaskResult(taskId, taskData.size(), parsed.finalAnswer, thoughtLog, toolCalls);
            }
            
            if (parsed.action != null) {
                log.info("[react] Action: {}", parsed.action);
                
                String toolResult = executeTool(parsed.action, parsed.actionInput);
                toolCalls.add(Map.of(
                    "iteration", String.valueOf(i + 1),
                    "tool", parsed.action,
                    "input", parsed.actionInput != null ? parsed.actionInput : "",
                    "result", toolResult.length() > 200 ? toolResult.substring(0, 200) + "..." : toolResult
                ));
                
                messages.add("Observation: " + toolResult);
            } else if (parsed.thought != null) {
                log.info("[react] Thought without Action, prompting to continue");
                messages.add("Assistant: " + response);
                messages.add("User: 请继续执行下一步。你必须输出 Action 和 Action Input，或者 Final Answer。");
            } else {
                messages.add("Assistant: " + response);
                messages.add("User: 请按照格式输出：Thought: ... Action: ... Action Input: ...");
            }
        }
        
        log.warn("[react] reached max iterations {}", MAX_ITERATIONS);
        return buildTaskResult(taskId, taskData.size(), "报告生成超时，请重试", thoughtLog, toolCalls);
    }

    /**
     * 查询任务数据
     */
    private List<Map<String, Object>> queryTaskData(String taskId) {
        return jdbcTemplate.queryForList(
                "SELECT r.section_id, cs.section_name, r.risk_level, " +
                "r.indicators->>'result' as risk_value, b.bank_name " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.task_id = ? AND r.deleted_at IS NULL " +
                "ORDER BY r.risk_level DESC",
                taskId
        );
    }

    /**
     * 构建任务级用户消息
     */
    private String buildTaskUserMessage(String taskId, List<Map<String, Object>> taskData) {
        return String.format("请生成任务 %s 的风险评估报告。", taskId);
    }

    /**
     * 构建任务级结果
     */
    private Map<String, Object> buildTaskResult(String taskId, int sectionsCount, String finalAnswer,
                                                 List<Map<String, String>> thoughtLog,
                                                 List<Map<String, String>> toolCalls) {
        // 生成 Markdown 报告
        String markdownPath = null;
        try {
            markdownPath = markdownReportService.generateMarkdownReport(taskId, finalAnswer, toolCalls);
            log.info("[react] markdown report generated: {}", markdownPath);
        } catch (Exception e) {
            log.warn("[react] failed to generate markdown report", e);
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        result.put("sections_count", sectionsCount);
        result.put("report", finalAnswer);
        result.put("markdown_path", markdownPath);
        result.put("thought_log", thoughtLog);
        result.put("tool_calls", toolCalls);
        result.put("iterations", thoughtLog.size());
        return result;
    }

    /**
     * 解析 LLM 响应
     */
    private ReActResponse parseResponse(String response) {
        ReActResponse parsed = new ReActResponse();
        
        // 提取 Thought
        Pattern thoughtPattern = Pattern.compile("Thought[:：]\\s*(.+?)(?=Action[:：]|Final Answer[:：]|$)", Pattern.DOTALL);
        Matcher thoughtMatcher = thoughtPattern.matcher(response);
        if (thoughtMatcher.find()) {
            parsed.thought = thoughtMatcher.group(1).trim();
        }
        
        // 提取 Final Answer
        Pattern finalPattern = Pattern.compile("Final Answer[:：]\\s*(.+)", Pattern.DOTALL);
        Matcher finalMatcher = finalPattern.matcher(response);
        if (finalMatcher.find()) {
            parsed.finalAnswer = finalMatcher.group(1).trim();
            return parsed;
        }
        
        // 提取 Action
        Pattern actionPattern = Pattern.compile("Action[:：]\\s*(.+?)$", Pattern.MULTILINE);
        Matcher actionMatcher = actionPattern.matcher(response);
        if (actionMatcher.find()) {
            parsed.action = actionMatcher.group(1).trim();
        }
        
        // 提取 Action Input
        Pattern inputPattern = Pattern.compile("Action Input[:：]\\s*(.+?)$", Pattern.MULTILINE);
        Matcher inputMatcher = inputPattern.matcher(response);
        if (inputMatcher.find()) {
            parsed.actionInput = inputMatcher.group(1).trim();
        }
        
        return parsed;
    }

    /**
     * 执行工具
     */
    private String executeTool(String toolName, String input) {
        Map<String, Function<Map<String, String>, String>> executors = toolExecutors.getAllExecutors();
        
        if (!executors.containsKey(toolName)) {
            return "Error: Unknown tool " + toolName;
        }
        
        try {
            // 解析输入参数
            Map<String, String> args = new HashMap<>();
            if (input != null && !input.isBlank()) {
                // 简单解析 key=value 格式
                for (String pair : input.split(",")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        args.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }
            
            return executors.get(toolName).apply(args);
        } catch (Exception e) {
            log.error("[react] tool execution failed: {}", toolName, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 构建结果
     */
    private Map<String, Object> buildResult(String sectionId, String finalAnswer, 
                                             List<Map<String, String>> thoughtLog,
                                             List<Map<String, String>> toolCalls) {
        // 生成 Markdown 报告
        String markdownPath = null;
        try {
            markdownPath = markdownReportService.generateMarkdownReport(sectionId, finalAnswer, toolCalls);
            log.info("[react] markdown report generated: {}", markdownPath);
        } catch (Exception e) {
            log.warn("[react] failed to generate markdown report", e);
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("section_id", sectionId);
        result.put("report", finalAnswer);
        result.put("markdown_path", markdownPath);
        result.put("thought_log", thoughtLog);
        result.put("tool_calls", toolCalls);
        result.put("iterations", thoughtLog.size());
        return result;
    }

    /**
     * ReAct 系统提示词
     */
    private String getReActSystemPrompt() {
        return """
            你是一个水利工程 AI 助手，负责生成风险评估报告。
            
            【核心规则】你必须使用工具获取真实数据，禁止编造数据。
            
            【回答格式】你必须严格按照以下格式回答，每行一个：
            
            Thought: 我需要查询数据
            Action: query_risk_data
            Action Input: task_id=task-xxx
            
            然后等待系统返回 Observation，再继续：
            
            Thought: 数据已获取，现在生成图表
            Action: generate_risk_distribution_map
            Action Input: task_id=task-xxx
            
            最后：
            
            Thought: 所有数据和图表已准备好，现在写报告
            Final Answer: （在这里写完整报告）
            
            【可用工具】
            - query_risk_data: 查询风险数据，参数格式：task_id=任务ID
            - generate_risk_distribution_map: 生成风险分布图，参数格式：task_id=任务ID
            
            【示例对话】
            用户：请生成任务 task-001 的报告
            
            Thought: 我需要先查询任务的风险数据
            Action: query_risk_data
            Action Input: task_id=task-001
            
            （系统返回 Observation：查询到 73 个断面数据...）
            
            Thought: 数据已获取，现在生成风险分布图
            Action: generate_risk_distribution_map
            Action Input: task_id=task-001
            
            （系统返回 Observation：图表生成成功...）
            
            Thought: 数据和图表都已准备好，现在写报告
            Final Answer: ## 风险评估报告...
            """;
    }

    /**
     * 构建单断面用户消息
     */
    @SuppressWarnings("unchecked")
    private String buildUserMessage(Map<String, Object> data) {
        String sectionId = String.valueOf(data.get("section_id"));
        return String.format("请生成断面 %s 的风险评估报告。", sectionId);
    }

    /**
     * 查询断面数据
     */
    private Map<String, Object> querySectionData(String sectionId) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT r.*, cs.section_name, b.bank_name " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.section_id = ? AND r.deleted_at IS NULL " +
                "ORDER BY r.id DESC LIMIT 1",
                sectionId
        );
        return results.isEmpty() ? Collections.emptyMap() : results.get(0);
    }

    /**
     * ReAct 响应解析结果
     */
    private static class ReActResponse {
        String thought;
        String action;
        String actionInput;
        String finalAnswer;
    }
}
