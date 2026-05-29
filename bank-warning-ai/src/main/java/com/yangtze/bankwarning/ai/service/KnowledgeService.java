package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.model.RiskKnowledge;
import com.yangtze.bankwarning.ai.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private final VectorStoreService vectorStoreService;
    private final LlmClient llmClient;
    private final Prompts prompts;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeService(VectorStoreService vectorStoreService, LlmClient llmClient,
                            Prompts prompts, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.vectorStoreService = vectorStoreService;
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void importCase(RiskKnowledge knowledge) {
        String content = String.format("""
                【历史崩塌案例】%s
                案例编号：%s
                区域：%s
                风险等级：%s
                
                案例描述：
                %s
                
                经验教训：
                %s
                """, knowledge.getTitle(), knowledge.getId(), knowledge.getRegion(),
                knowledge.getRiskLevel(), knowledge.getContent(), knowledge.getSource());

        Map<String, Object> metadata = Map.of("type", "case", "region", 
                knowledge.getRegion() != null ? knowledge.getRegion() : "",
                "riskLevel", knowledge.getRiskLevel() != null ? knowledge.getRiskLevel() : "");

        vectorStoreService.addDocument("case_" + knowledge.getId(), content, metadata);
        log.info("[knowledge] imported case: {}", knowledge.getTitle());
    }

    public void importStandard(RiskKnowledge knowledge) {
        String content = String.format("""
                【规范标准】%s
                标准编号：%s
                适用范围：%s
                
                具体内容：
                %s
                """, knowledge.getTitle(), knowledge.getId(),
                knowledge.getRegion(), knowledge.getContent());

        Map<String, Object> metadata = Map.of("type", "standard", "standardId", knowledge.getId());

        vectorStoreService.addDocument("standard_" + knowledge.getId(), content, metadata);
        log.info("[knowledge] imported standard: {}", knowledge.getTitle());
    }

    public void importExperience(RiskKnowledge knowledge) {
        String content = String.format("""
                【专家经验】%s
                适用场景：%s
                专家来源：%s
                
                经验内容：
                %s
                """, knowledge.getTitle(), knowledge.getRegion(),
                knowledge.getSource(), knowledge.getContent());

        Map<String, Object> metadata = Map.of("type", "experience", "expert",
                knowledge.getSource() != null ? knowledge.getSource() : "");

        vectorStoreService.addDocument("exp_" + knowledge.getId(), content, metadata);
        log.info("[knowledge] imported experience: {}", knowledge.getTitle());
    }

    public int importHistoricalRiskData(String taskId) {
        List<Map<String, Object>> results;
        if (taskId != null) {
            results = jdbcTemplate.queryForList(
                    "SELECT r.*, cs.section_name FROM bank_risk_results r " +
                    "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                    "WHERE r.deleted_at IS NULL AND r.task_id = ?", taskId);
        } else {
            results = jdbcTemplate.queryForList(
                    "SELECT r.*, cs.section_name FROM bank_risk_results r " +
                    "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                    "WHERE r.deleted_at IS NULL");
        }

        int count = 0;
        for (Map<String, Object> result : results) {
            try {
                String sectionId = result.get("section_id").toString();
                String sectionName = result.get("section_name") != null ?
                        result.get("section_name").toString() : sectionId;
                String indicatorsJson = result.get("indicators") != null ?
                        result.get("indicators").toString() : "{}";

                @SuppressWarnings("unchecked")
                Map<String, Object> indicators = objectMapper.readValue(indicatorsJson, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> rawValues = (Map<String, Object>) indicators.getOrDefault("raw_values", Collections.emptyMap());

                String content = String.format("""
                        【历史风险记录】
                        断面：%s（%s）
                        风险等级：%s 级
                        综合风险值：%s
                        
                        关键指标：
                        - 水动力：Ky=%s, PQ=%s, Zd=%s
                        - 河床演变：Sa=%s, Ln=%s, Zb=%s
                        - 地质工程：Dsed=%s
                        """, sectionName, sectionId, result.get("risk_level"), indicators.get("result"),
                        rawValues.get("Ky"), rawValues.get("PQ"), rawValues.get("Zd"),
                        rawValues.get("Sa"), rawValues.get("Ln"), rawValues.get("Zb"),
                        rawValues.get("Dsed"));

                Map<String, Object> metadata = Map.of("type", "historical",
                        "sectionId", sectionId, "riskLevel", 
                        result.get("risk_level") != null ? result.get("risk_level").toString() : "");

                vectorStoreService.addDocument("hist_" + sectionId + "_" + System.currentTimeMillis(), content, metadata);
                count++;
            } catch (Exception e) {
                log.warn("[knowledge] import failed for section={}", result.get("section_id"), e);
            }
        }

        log.info("[knowledge] imported {} historical records", count);
        return count;
    }

    public String ask(String question) {
        log.info("[knowledge] question: {}", question);

        List<Map<String, Object>> searchResults = vectorStoreService.search(question, 5, null);

        if (searchResults.isEmpty()) {
            log.info("[knowledge] no references found, using direct LLM");
            return llmClient.chat(prompts.getQaSystem(), question);
        }

        String prompt = prompts.buildRagPrompt(question, searchResults);
        String answer = llmClient.chat(prompts.getQaSystem(), prompt);

        log.info("[knowledge] answer generated, refs={}", searchResults.size());
        return answer;
    }

    public List<Map<String, Object>> searchKnowledge(String query, String type, int topK) {
        return vectorStoreService.search(query, topK, type);
    }

    public Map<String, Object> getStats() {
        return Map.of("totalDocuments", vectorStoreService.getDocumentCount());
    }
}
