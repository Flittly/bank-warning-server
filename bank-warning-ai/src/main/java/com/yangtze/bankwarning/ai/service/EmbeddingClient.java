package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private final AiProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getLlm().getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getLlm().getApiKey())
                .build();
    }

    public float[] embed(String text) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", properties.getEmbedding().getModel());
        request.put("input", text);

        try {
            String responseBody = webClient.post()
                    .uri("/embeddings")
                    .bodyValue(request.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(properties.getEmbedding().getTimeout()))
                    .block();

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || data.isEmpty()) {
                throw new IllegalStateException("No embedding data");
            }

            JsonNode embedding = data.get(0).get("embedding");
            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = (float) embedding.get(i).asDouble();
            }

            log.info("[embedding] success, dims={}", result.length);
            return result;
        } catch (Exception e) {
            log.error("[embedding] failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Embedding failed: " + e.getMessage(), e);
        }
    }
}
