package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yangtze.bankwarning.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private final AiProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public LlmClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getLlm().getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getLlm().getApiKey())
                .build();
    }

    /**
     * 普通对话（无工具）
     */
    public String chat(String systemPrompt, String userMessage) {
        ObjectNode request = buildRequest(systemPrompt, userMessage, null);
        log.info("[llm] calling model={}", properties.getLlm().getModel());

        try {
            String responseBody = callLlm(request);
            return extractContent(responseBody);
        } catch (Exception e) {
            log.error("[llm] call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 带工具调用的对话（Agent 模式）
     */
    public AgentResponse chatWithTools(String systemPrompt, String userMessage, 
                                        List<ToolDefinition> tools, 
                                        Map<String, Function<Map<String, String>, String>> toolExecutors) {
        ObjectNode request = buildRequest(systemPrompt, userMessage, tools);
        log.info("[llm-agent] calling with {} tools", tools.size());

        try {
            // 第一次调用
            String responseBody = callLlm(request);
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("No choices in response");
            }

            JsonNode message = choices.get(0).get("message");
            JsonNode toolCalls = message.get("tool_calls");
            
            // 如果 LLM 没有调用工具，直接返回文本
            if (toolCalls == null || toolCalls.isEmpty() || toolCalls.isNull()) {
                String content = message.get("content").asText();
                log.info("[llm-agent] no tool calls, returning text");
                return new AgentResponse(content, Collections.emptyList());
            }

            // LLM 调用了工具，执行工具并继续对话
            log.info("[llm-agent] LLM requested {} tool calls", toolCalls.size());
            
            // 构建包含工具调用的消息
            ArrayNode messages = (ArrayNode) request.get("messages").deepCopy();
            messages.add(message);
            
            List<ToolCallResult> toolResults = new ArrayList<>();
            
            // 执行每个工具调用
            for (JsonNode toolCall : toolCalls) {
                String id = toolCall.get("id").asText();
                String functionName = toolCall.get("function").get("name").asText();
                String arguments = toolCall.get("function").get("arguments").asText();
                
                log.info("[llm-agent] executing tool: {}", functionName);
                
                // 解析参数
                @SuppressWarnings("unchecked")
                Map<String, String> args = objectMapper.readValue(arguments, Map.class);
                
                // 执行工具
                String result = "";
                if (toolExecutors.containsKey(functionName)) {
                    try {
                        result = toolExecutors.get(functionName).apply(args);
                    } catch (Exception e) {
                        log.error("[llm-agent] tool execution failed: {}", functionName, e);
                        result = "Error: " + e.getMessage();
                    }
                } else {
                    result = "Error: Unknown tool " + functionName;
                }
                
                toolResults.add(new ToolCallResult(id, functionName, result));
                
                // 添加工具结果到消息
                ObjectNode toolResultMsg = objectMapper.createObjectNode();
                toolResultMsg.put("role", "tool");
                toolResultMsg.put("tool_call_id", id);
                toolResultMsg.put("content", result);
                messages.add(toolResultMsg);
            }
            
            // 第二次调用，让 LLM 基于工具结果生成最终回答
            request.set("messages", messages);
            responseBody = callLlm(request);
            root = objectMapper.readTree(responseBody);
            choices = root.get("choices");
            
            String finalContent = "";
            if (choices != null && !choices.isEmpty()) {
                finalContent = choices.get(0).get("message").get("content").asText();
            }
            
            log.info("[llm-agent] final response generated with {} tool results", toolResults.size());
            return new AgentResponse(finalContent, toolResults);
            
        } catch (Exception e) {
            log.error("[llm-agent] call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("LLM agent call failed: " + e.getMessage(), e);
        }
    }

    private String callLlm(ObjectNode request) {
        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(request.toString())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getLlm().getTimeout()))
                .block();
    }

    private ObjectNode buildRequest(String systemPrompt, String userMessage, List<ToolDefinition> tools) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", properties.getLlm().getModel());
        request.put("temperature", properties.getLlm().getTemperature());
        request.put("max_tokens", properties.getLlm().getMaxTokens());
        request.put("stream", false);

        ArrayNode messages = objectMapper.createArrayNode();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
        }

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        request.set("messages", messages);

        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = objectMapper.createObjectNode();
                toolNode.put("type", "function");
                
                ObjectNode functionNode = objectMapper.createObjectNode();
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
                
                toolNode.set("function", functionNode);
                toolsArray.add(toolNode);
            }
            request.set("tools", toolsArray);
        }

        return request;
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("No choices in response");
            }
            String content = choices.get(0).get("message").get("content").asText();
            log.info("[llm] response length={}", content.length());
            return content;
        } catch (Exception e) {
            log.error("[llm] parse failed: {}", e.getMessage());
            throw new IllegalStateException("Failed to parse LLM response", e);
        }
    }

    /**
     * 工具定义
     */
    public record ToolDefinition(String name, String description, JsonNode parameters) {}

    /**
     * 工具调用结果
     */
    public record ToolCallResult(String id, String functionName, String result) {}

    /**
     * Agent 响应
     */
    public record AgentResponse(String content, List<ToolCallResult> toolResults) {
        public boolean hasToolCalls() {
            return toolResults != null && !toolResults.isEmpty();
        }
    }
}
