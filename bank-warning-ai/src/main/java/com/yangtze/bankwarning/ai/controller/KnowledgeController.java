package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.PdfService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v0/bank/ai")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private final Knowledge knowledge;
    private final PdfService pdfService;

    private static final int MAX_CHUNK_SIZE = 500;

    public KnowledgeController(Knowledge knowledge, PdfService pdfService) {
        this.knowledge = knowledge;
        this.pdfService = pdfService;
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

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "type", defaultValue = "文档") String type,
            @RequestParam(name = "title", required = false) String title) {
        log.info("[api] upload pdf, fileName={}, size={}, type={}", file.getOriginalFilename(), file.getSize(), type);

        if (file.isEmpty()) {
            return Map.of("success", false, "error", "上传文件为空");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            return Map.of("success", false, "error", "仅支持 PDF 文件");
        }
        String docTitle = (title != null && !title.isBlank()) ? title : fileName;

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("knowledge-", ".pdf");
            file.transferTo(tempFile.toFile());

            Map<String, Object> result = pdfService.processPdf("pdf", "extract_text.py", tempFile.toString());
            if (!Boolean.TRUE.equals(result.get("success"))) {
                return result;
            }
            String text = (String) result.get("content");
            if (text == null || text.isBlank()) {
                return Map.of("success", false, "error", "PDF 中未提取到文本内容（可能是扫描件，暂不支持 OCR）");
            }

            List<String> chunks = chunkText(text);
            List<Document> docs = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentMetadata metadata = DocumentMetadata.builder()
                        .content(TextBlock.builder().text(chunks.get(i)).build())
                        .docId(docTitle)
                        .addPayload("type", type)
                        .addPayload("chunkIndex", String.valueOf(i))
                        .addPayload("fileName", fileName)
                        .addPayload("totalChunks", String.valueOf(chunks.size()))
                        .build();
                docs.add(new Document(metadata));
            }

            knowledge.addDocuments(docs).block();
            log.info("[api] pdf imported, title={}, chunks={}", docTitle, chunks.size());

            return Map.of("success", true, "docId", docTitle, "chunks", chunks.size(), "type", type);

        } catch (Exception e) {
            log.error("[api] upload failed", e);
            return Map.of("success", false, "error", "导入失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
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

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder current = new StringBuilder();

        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (trimmed.isBlank()) continue;
            if (current.length() + trimmed.length() > MAX_CHUNK_SIZE && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(trimmed);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        if (chunks.isEmpty() && !text.isBlank()) {
            chunks.add(text.trim());
        }
        return chunks;
    }

    @GetMapping("/knowledge/stats")
    public Map<String, Object> getStats() {
        return Map.of("status", "PgVectorStore", "note", "统计信息请直接查询数据库 ai_knowledge_store 表");
    }
}
