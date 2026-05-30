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

    public ReActAgentService(LlmClient llmClient, Prompts prompts,
                              JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              VisualizationToolExecutors toolExecutors) {
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.toolExecutors = toolExecutors;
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
            } else {
                // 如果既没有 Final Answer 也没有 Action，可能是纯文本思考
                // 将其添加到消息中继续
                messages.add("Assistant: " + response);
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
            } else {
                messages.add("Assistant: " + response);
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
        long highRisk = taskData.stream()
                .filter(d -> d.get("risk_level") instanceof Number n && n.intValue() >= 3)
                .count();

        return String.format("""
            请为任务 %s 生成风险评估汇总报告。
            
            任务包含 %d 个断面，其中高风险（3-4级）%d 个。
            
            【必须执行的步骤】
            第一步：调用 query_risk_data 工具，参数 task_id=%s
            第二步：调用 generate_risk_distribution_map 工具，参数 task_id=%s
            第三步：根据工具返回的真实数据，撰写汇总报告
            
            请立即开始第一步：调用 query_risk_data 工具。
            """, taskId, taskData.size(), highRisk, taskId, taskId);
    }

    /**
     * 构建任务级结果
     */
    private Map<String, Object> buildTaskResult(String taskId, int sectionsCount, String finalAnswer,
                                                 List<Map<String, String>> thoughtLog,
                                                 List<Map<String, String>> toolCalls) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        result.put("sections_count", sectionsCount);
        result.put("report", finalAnswer);
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("section_id", sectionId);
        result.put("report", finalAnswer);
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
            你是一名资深的水利工程专家，专门从事长江河岸崩塌风险评估工作。
            
            【重要规则】你必须严格按照以下格式回答，不能跳过任何步骤：
            
            Thought: 分析当前情况，决定下一步行动
            Action: 工具名称
            Action Input: task_id=xxx 或 section_id=xxx
            Observation: 工具返回的结果（等待系统填入）
            ... (重复直到收集到足够数据)
            Thought: 数据已收集完毕，现在生成报告
            Final Answer: 最终的完整报告
            
            【严格要求】
            1. 你必须先调用 query_risk_data 工具获取真实数据
            2. 你必须调用 generate_risk_distribution_map 生成图表
            3. 只有在获得工具返回的真实数据后，才能写报告
            4. 禁止编造数据或图表路径
            5. 每次只能调用一个工具
            6. 等待 Observation 结果后再继续
            
            可用工具：
            - query_risk_data: 查询风险数据（参数：task_id=任务ID）
            - generate_risk_distribution_map: 生成风险分布图（参数：task_id=任务ID）
            """;
    }

    /**
     * 构建单断面用户消息
     */
    @SuppressWarnings("unchecked")
    private String buildUserMessage(Map<String, Object> data) {
        String sectionName = String.valueOf(data.getOrDefault("section_name", "未知断面"));
        String bankName = String.valueOf(data.getOrDefault("bank_name", ""));
        Object riskLevel = data.get("risk_level");
        String sectionId = String.valueOf(data.get("section_id"));

        return String.format("""
            请为断面 %s（%s）生成风险评估报告。
            当前风险等级：%s 级
            断面ID：%s
            
            【必须执行的步骤】
            第一步：调用 query_risk_data 工具，参数 section_id=%s
            第二步：根据工具返回的真实数据，撰写分析报告
            
            请立即开始第一步：调用 query_risk_data 工具。
            """, sectionName, bankName, riskLevel, sectionId, sectionId);
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
