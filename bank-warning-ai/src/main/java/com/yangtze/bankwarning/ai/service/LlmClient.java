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

    public String chat(String systemPrompt, String userMessage) {
        ObjectNode request = buildRequest(systemPrompt, userMessage);
        log.info("[llm] calling model={}", properties.getLlm().getModel());

        try {
            String responseBody = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(properties.getLlm().getTimeout()))
                    .block();

            return extractContent(responseBody);
        } catch (Exception e) {
            log.error("[llm] call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildRequest(String systemPrompt, String userMessage) {
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
}
