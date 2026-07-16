package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.PdfService;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v0/bank/ai")
@SuppressWarnings({"deprecation", "removal"})
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private final KnowledgeService knowledgeService;
    private final PdfService pdfService;
    private final JdbcTemplate jdbcTemplate;

    private static final int MAX_CHUNK_SIZE = 500;

    public KnowledgeController(KnowledgeService knowledgeService, PdfService pdfService, JdbcTemplate jdbcTemplate) {
        this.knowledgeService = knowledgeService;
        this.pdfService = pdfService;
        this.jdbcTemplate = jdbcTemplate;
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
                .chunkId(title + "_chunk_0")
                .addPayload("type", type)
                .build();

        Document doc = new Document(metadata);
        knowledgeService.addDocuments(List.of(doc)).block();
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null) {
            jdbcTemplate.update("UPDATE ai_knowledge_store SET user_id = ? WHERE doc_id = ?", userId, title);
        }
        return Map.of("success", true, "docId", title);
    }

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "type", defaultValue = "文档") String type,
            @RequestParam(name = "title", required = false) String title) {
        log.info("[api] upload knowledge file, fileName={}, size={}, type={}", file.getOriginalFilename(), file.getSize(), type);

        if (file.isEmpty()) {
            return Map.of("success", false, "error", "上传文件为空");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            return Map.of("success", false, "error", "文件名无效");
        }

        String lowerName = fileName.toLowerCase();
        String skillName;
        String scriptName;
        String suffix;
        if (lowerName.endsWith(".pdf")) {
            skillName = "pdf";
            scriptName = "extract_text.py";
            suffix = ".pdf";
        } else if (lowerName.endsWith(".docx")) {
            skillName = "word";
            scriptName = "extract_text_docx.py";
            suffix = ".docx";
        } else {
            return Map.of("success", false, "error", "仅支持 PDF 和 DOCX 文件");
        }

        String docTitle = (title != null && !title.isBlank()) ? title : fileName;

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("knowledge-", suffix);
            file.transferTo(tempFile.toFile());

            Map<String, Object> result = pdfService.processPdf(skillName, scriptName, tempFile.toString());
            if (!Boolean.TRUE.equals(result.get("success"))) {
                return result;
            }
            String text = (String) result.get("content");
            if (text == null || text.isBlank()) {
                return Map.of("success", false, "error", "文件中未提取到文本内容");
            }

            List<String> chunks = chunkText(text);
            List<Document> docs = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentMetadata metadata = DocumentMetadata.builder()
                        .content(TextBlock.builder().text(chunks.get(i)).build())
                        .docId(docTitle)
                        .chunkId(docTitle + "_chunk_" + i)
                        .addPayload("type", type)
                        .addPayload("chunkIndex", String.valueOf(i))
                        .addPayload("fileName", fileName)
                        .addPayload("totalChunks", String.valueOf(chunks.size()))
                        .build();
                docs.add(new Document(metadata));
            }

            knowledgeService.addDocuments(docs).block();
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId != null) {
                jdbcTemplate.update("UPDATE ai_knowledge_store SET user_id = ? WHERE doc_id = ?", userId, docTitle);
            }
            log.info("[api] knowledge imported, title={}, chunks={}, file={}", docTitle, chunks.size(), fileName);

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

    @GetMapping("/knowledge/list")
    public List<Map<String, Object>> listDocuments() {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        String sql = "SELECT DISTINCT doc_id, payload FROM ai_knowledge_store WHERE (user_id = ? OR ?::bigint IS NULL) ORDER BY doc_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId, userId);
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String docId = (String) row.get("doc_id");
            Map<String, Object> meta = grouped.computeIfAbsent(docId, k -> new LinkedHashMap<>());
            meta.putIfAbsent("docId", docId);
            Object payload = row.get("payload");
            if (payload instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) payload;
                meta.putIfAbsent("type", p.getOrDefault("type", ""));
                meta.putIfAbsent("fileName", p.getOrDefault("fileName", docId));
            }
            Integer chunks = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_knowledge_store WHERE doc_id = ? AND (user_id = ? OR ?::bigint IS NULL)", Integer.class, docId, userId, userId);
            meta.put("chunks", chunks != null ? chunks : 0);
        }
        return new ArrayList<>(grouped.values());
    }

    @GetMapping("/knowledge/{id}")
    public Map<String, Object> getDocument(@PathVariable("id") String id) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT content, chunk_id, payload FROM ai_knowledge_store WHERE doc_id = ? AND (user_id = ? OR ?::bigint IS NULL) ORDER BY chunk_id", id, userId, userId);
        if (rows.isEmpty()) {
            return Map.of("success", false, "error", "文档不存在");
        }
        String fullText = rows.stream()
                .map(r -> (String) r.get("content"))
                .collect(Collectors.joining("\n\n---\n\n"));
        Map<String, Object> firstPayload = null;
        if (!rows.isEmpty()) {
            Object p = rows.get(0).get("payload");
            if (p instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mp = (Map<String, Object>) p;
                firstPayload = mp;
            }
        }
        return Map.of("success", true, "docId", id,
                "content", fullText,
                "chunks", rows.size(),
                "type", firstPayload != null ? firstPayload.getOrDefault("type", "") : "",
                "fileName", firstPayload != null ? firstPayload.getOrDefault("fileName", id) : id);
    }

    @DeleteMapping("/knowledge/{id}")
    public Map<String, Object> deleteDocument(@PathVariable("id") String id) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        int deleted = jdbcTemplate.update("DELETE FROM ai_knowledge_store WHERE doc_id = ? AND (user_id = ? OR ?::bigint IS NULL)", id, userId, userId);
        if (deleted == 0) {
            return Map.of("success", false, "error", "文档不存在");
        }
        log.info("[api] deleted knowledge, docId={}, rows={}", id, deleted);
        return Map.of("success", true, "deleted", deleted);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam("query") String query,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "topK", defaultValue = "5") int topK) {
        List<Document> results = knowledgeService.retrieve(query, topK, 0.4).block();
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
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        Integer docCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT doc_id) FROM ai_knowledge_store WHERE (user_id = ? OR ?::bigint IS NULL)", Integer.class, userId, userId);
        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_knowledge_store WHERE (user_id = ? OR ?::bigint IS NULL)", Integer.class, userId, userId);
        return Map.of("store", "PgVectorStore", "documents", docCount != null ? docCount : 0,
                "chunks", chunkCount != null ? chunkCount : 0, "dimensions", 1024);
    }
}
