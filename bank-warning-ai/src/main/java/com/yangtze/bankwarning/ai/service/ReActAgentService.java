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
            
            请用以下格式回答用户问题：
            
            Thought: 分析当前情况，决定下一步行动
            Action: 工具名称
            Action Input: 参数（格式：key=value, key2=value2）
            Observation: 工具返回的结果
            ... (可以重复多轮)
            Thought: 现在我可以得出结论了
            Final Answer: 最终的完整报告
            
            可用工具：
            - generate_risk_distribution_map: 生成风险分布图（参数：task_id, bank_id）
            - generate_scour_heatmap: 生成冲淤热力图（参数：section_id, task_id）
            - generate_section_comparison_chart: 生成断面对比图（参数：section_id, task_id）
            - query_risk_data: 查询风险数据（参数：task_id, bank_id, section_id）
            
            注意：
            1. 每次只调用一个工具
            2. 等待工具返回结果后再继续
            3. 最终必须给出 Final Answer
            """;
    }

    /**
     * 构建用户消息
     */
    @SuppressWarnings("unchecked")
    private String buildUserMessage(Map<String, Object> data) {
        String sectionName = String.valueOf(data.getOrDefault("section_name", "未知断面"));
        String bankName = String.valueOf(data.getOrDefault("bank_name", ""));
        Object riskLevel = data.get("risk_level");

        return String.format("""
            请为断面 %s（%s）生成风险评估报告。
            当前风险等级：%s 级
            
            请先查询详细数据，然后生成合适的图表，最后给出完整的分析报告。
            """, sectionName, bankName, riskLevel);
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
