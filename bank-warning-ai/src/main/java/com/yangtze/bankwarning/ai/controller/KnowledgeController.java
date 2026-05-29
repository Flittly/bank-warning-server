package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.model.RiskKnowledge;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v0/bank/ai")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestParam("question") String question) {
        log.info("[api] question: {}", question);
        String answer = knowledgeService.ask(question);
        return Map.of("success", true, "question", question, "answer", answer);
    }

    @PostMapping("/knowledge")
    public Map<String, Object> importKnowledge(@RequestBody RiskKnowledge knowledge) {
        log.info("[api] import type={}", knowledge.getType());
        switch (knowledge.getType()) {
            case "case" -> knowledgeService.importCase(knowledge);
            case "standard" -> knowledgeService.importStandard(knowledge);
            case "experience" -> knowledgeService.importExperience(knowledge);
            default -> throw new IllegalArgumentException("未知类型: " + knowledge.getType());
        }
        return Map.of("success", true, "message", "导入成功");
    }

    @PostMapping("/knowledge/import-history")
    public Map<String, Object> importHistoricalData(
            @RequestParam(name = "task_id", required = false) String taskId) {
        log.info("[api] import history, taskId={}", taskId);
        int count = knowledgeService.importHistoricalRiskData(taskId);
        return Map.of("success", true, "imported", count);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam("query") String query,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "topK", defaultValue = "5") int topK) {
        return knowledgeService.searchKnowledge(query, type, topK);
    }

    @GetMapping("/knowledge/stats")
    public Map<String, Object> getStats() {
        return knowledgeService.getStats();
    }
}
