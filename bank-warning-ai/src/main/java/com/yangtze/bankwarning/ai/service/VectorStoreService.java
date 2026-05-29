package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public VectorStoreService(JdbcTemplate jdbcTemplate, EmbeddingClient embeddingClient,
                              AiProperties properties, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void addDocument(String id, String content, Map<String, Object> metadata) {
        float[] embedding = embeddingClient.embed(content);
        String embeddingStr = toVectorString(embedding);
        String metadataJson = toJson(metadata);

        jdbcTemplate.update(
                "INSERT INTO ai_knowledge_store (id, content, metadata, embedding) " +
                "VALUES (?, ?, ?::jsonb, ?::vector) " +
                "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, " +
                "metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding, updated_at = CURRENT_TIMESTAMP",
                id, content, metadataJson, embeddingStr
        );

        log.info("[vector] added id={}", id);
    }

    public List<Map<String, Object>> search(String query, int topK, String filterType) {
        float[] queryEmbedding = embeddingClient.embed(query);
        String embeddingStr = toVectorString(queryEmbedding);
        double threshold = properties.getVector().getSimilarityThreshold();

        String sql;
        List<Object> params = new ArrayList<>();

        if (filterType != null && !filterType.isBlank()) {
            sql = "SELECT id, content, metadata, 1 - (embedding <=> ?::vector) AS similarity " +
                  "FROM ai_knowledge_store WHERE deleted_at IS NULL AND metadata->>'type' = ? " +
                  "AND 1 - (embedding <=> ?::vector) >= ? ORDER BY embedding <=> ?::vector LIMIT ?";
            params.addAll(List.of(embeddingStr, filterType, embeddingStr, threshold, embeddingStr, topK));
        } else {
            sql = "SELECT id, content, metadata, 1 - (embedding <=> ?::vector) AS similarity " +
                  "FROM ai_knowledge_store WHERE deleted_at IS NULL " +
                  "AND 1 - (embedding <=> ?::vector) >= ? ORDER BY embedding <=> ?::vector LIMIT ?";
            params.addAll(List.of(embeddingStr, embeddingStr, threshold, embeddingStr, topK));
        }

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params.toArray());
        log.info("[vector] search='{}', results={}", query, results.size());
        return results;
    }

    public int getDocumentCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_knowledge_store WHERE deleted_at IS NULL", Integer.class);
        return count != null ? count : 0;
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
