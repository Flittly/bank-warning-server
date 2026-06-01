package com.yangtze.bankwarning.ai.controller;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v0/bank/ai")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private final Knowledge knowledge;

    public KnowledgeController(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    @PostMapping("/knowledge")
    public Map<String, Object> importKnowledge(@RequestBody Map<String, Object> body) {
        String type = (String) body.get("type");
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        log.info("[api] import knowledge type={}, title={}", type, title);

        DocumentMetadata metadata = DocumentMetadata.builder()
                .content(TextBlock.builder().text(content).build())
                .docId(title)
                .addPayload("type", type)
                .build();

        Document doc = new Document(metadata);
        knowledge.addDocuments(List.of(doc)).block();
        return Map.of("success", true, "docId", title);
    }

    @PostMapping("/knowledge/import-history")
    public Map<String, Object> importHistoricalData(
            @RequestParam(name = "task_id", required = false) String taskId) {
        log.info("[api] import history, taskId={}", taskId);
        return Map.of("success", true, "message", "启动时自动导入历史数据，无需手动触发");
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam("query") String query,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "topK", defaultValue = "5") int topK) {
        RetrieveConfig config = RetrieveConfig.builder()
                .limit(topK)
                .scoreThreshold(0.4)
                .build();
        List<Document> results = knowledge.retrieve(query, config).block();
        if (results == null) return List.of();
        return results.stream().map(doc -> Map.<String, Object>of(
                "content", doc.getMetadata().getContent().toString(),
                "score", doc.getScore(),
                "docId", doc.getMetadata().getDocId()
        )).collect(Collectors.toList());
    }

    @GetMapping("/knowledge/stats")
    public Map<String, Object> getStats() {
        return Map.of("totalDocuments", "N/A", "status", "InMemoryStore");
    }
}
