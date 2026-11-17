package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.KnowledgeUnavailableException;
import com.yangtze.bankwarning.ai.service.PdfService;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    private final RagProperties ragProps;
    private final EmbeddingBatchProperties batchProps;

    private static final int MAX_CHUNK_SIZE = 500;

    public KnowledgeController(KnowledgeService knowledgeService, PdfService pdfService,
                               JdbcTemplate jdbcTemplate, RagProperties ragProps,
                               EmbeddingBatchProperties batchProps) {
        this.knowledgeService = knowledgeService;
        this.pdfService = pdfService;
        this.jdbcTemplate = jdbcTemplate;
        this.ragProps = ragProps;
        this.batchProps = batchProps;
    }

    /**
     * 入库并等结果，<b>必须带超时</b>。
     *
     * <p>改造前是 {@code addDocuments(docs).block()}——无参 {@code block()} 是「永远等」，
     * 100 段的请求就靠 nginx 的 900s 去砍。现在按批量嵌入的总预算兜一层，
     * 超时抛出明确异常由下方 catch 转成 {@code success:false}。
     *
     * <p>关闭批量嵌入时这个上限也生效（{@code ingestBudgetMs} 默认 180s）：
     * 逐段串行超过 3 分钟就不该继续占着 Tomcat 线程，让它失败比让它挂着好。
     */
    private KnowledgeService.IngestResult ingest(List<Document> docs) {
        long waitMs = batchProps.ingestBudgetMs + 15_000L;
        return knowledgeService.addDocumentsWithResult(docs).block(Duration.ofMillis(waitMs));
    }

    /**
     * 列表/详情的可见口径必须与检索一致：管理员看全部、普通用户看「自己 + 公共」。
     * 关掉 public-knowledge-enabled 就退回阶段 1 的严格私有口径（也可用于列未就绪时应急）。
     * 删除操作刻意不套用这个条件：公共规范只有归属者或管理员能删。
     */
    private String visibilityClause() {
        return ragProps.publicKnowledgeEnabled
                ? "(user_id = ? OR ?::bigint IS NULL OR is_public = TRUE)"
                : "(user_id = ? OR ?::bigint IS NULL)";
    }

    @PostMapping("/knowledge")
    public Map<String, Object> importKnowledge(@RequestBody Map<String, Object> body) {
        String type = (String) body.get("type");
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            return Map.of("success", false, "error", "标题或内容为空");
        }
        log.info("[api] import knowledge type={}, title={}", type, title);

        // 与 /knowledge/upload 走同一套切分。文本直传曾把整篇写成一个 chunk_0，
        // 长文件（如 riskLevel.py）会退化成单段，检索粒度与 PDF 路径不一致。
        List<String> chunks = chunkText(content);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .content(TextBlock.builder().text(chunks.get(i)).build())
                    .docId(title)
                    .chunkId(title + "_chunk_" + i)
                    .addPayload("type", type)
                    .addPayload("chunkIndex", String.valueOf(i))
                    .addPayload("totalChunks", String.valueOf(chunks.size()))
                    .build();
            docs.add(new Document(metadata));
        }
        KnowledgeService.IngestResult ingest = ingest(docs);
        // 默认私有；公共资料由上传界面显式勾选 isPublic=true。
        boolean isPublic = parseBoolean(body.get("isPublic"));
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null) {
            jdbcTemplate.update("UPDATE ai_knowledge_store SET user_id = ?, is_public = ? WHERE doc_id = ?",
                    userId, isPublic, title);
        } else {
            jdbcTemplate.update("UPDATE ai_knowledge_store SET is_public = ? WHERE doc_id = ?",
                    isPublic, title);
        }
        // success 不再恒为 true：全部段嵌入失败时前端必须能看见，而不是只看到 chunks>0。
        return Map.of("success", ingest.ok() > 0, "docId", title, "chunks", chunks.size(),
                "embedded", ingest.ok(), "failed", ingest.failed(), "isPublic", isPublic);
    }

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "type", defaultValue = "文档") String type,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "isPublic", defaultValue = "false") boolean isPublic) {
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

            KnowledgeService.IngestResult ingest = ingest(docs);
            // 阶段 2：公共标记与归属分开写，未登录时也要能把公共标记落库。
            Long userId = SecurityUtils.getCurrentUserId();
            if (userId != null) {
                jdbcTemplate.update("UPDATE ai_knowledge_store SET user_id = ?, is_public = ? WHERE doc_id = ?",
                        userId, isPublic, docTitle);
            } else {
                jdbcTemplate.update("UPDATE ai_knowledge_store SET is_public = ? WHERE doc_id = ?",
                        isPublic, docTitle);
            }
            log.info("[api] knowledge imported, title={}, chunks={}, embedded={}, failed={}, file={}, isPublic={}",
                    docTitle, chunks.size(), ingest.ok(), ingest.failed(), fileName, isPublic);

            return Map.of("success", ingest.ok() > 0, "docId", docTitle, "chunks", chunks.size(),
                    "embedded", ingest.ok(), "failed", ingest.failed(),
                    "type", type, "isPublic", isPublic);

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
        String sql = "SELECT DISTINCT doc_id, payload FROM ai_knowledge_store WHERE "
                + visibilityClause() + " ORDER BY doc_id";
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
                    "SELECT COUNT(*) FROM ai_knowledge_store WHERE doc_id = ? AND "
                            + visibilityClause(), Integer.class, docId, userId, userId);
            meta.put("chunks", chunks != null ? chunks : 0);
        }
        return new ArrayList<>(grouped.values());
    }

    @GetMapping("/knowledge/{id}")
    public Map<String, Object> getDocument(@PathVariable("id") String id) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT content, chunk_id, payload FROM ai_knowledge_store WHERE doc_id = ? AND "
                        + visibilityClause() + " ORDER BY chunk_id", id, userId, userId);
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
            @RequestParam(name = "topK", required = false) Integer topK) {
        // 这里仍在 Tomcat 请求线程上，SecurityContext 有效，可以直接取身份。
        AiCaller caller = AiCaller.of(SecurityUtils.getDataScope());
        List<Document> results = knowledgeService.retrieve(query, KnowledgeService.Scenario.SEARCH, caller, topK);
        return results.stream().map(KnowledgeController::toHitMap).collect(Collectors.toList());
    }

    /**
     * 命中项 → 返回给前端的 JSON 对象。
     *
     * <p><b>这里不能用 {@code Map.of}</b>：它拒绝 null 值，遇到 null 直接抛 NPE。
     * 而 {@code Document.score} 是可空的 {@code Double}——混合检索打开后，
     * 「向量路 0 条 + 关键词路命中」时 {@code RrfFusion} 会原样返回 sparse 文档，
     * 这些文档从头到尾没有设过分。旧实现把 {@code doc.getScore()} 直接塞进
     * {@code Map.of}，于是这条路径以 500 结束，而不是返回「没有相似度」。
     *
     * <p>另外一个附带信息是 {@code scored}：前端必须能区分「相似度 0.000」与
     * 「这条没有分数」。前者是向量路真算了分，后者是关键词命中，显示成相似度就是误导。
     */
    static Map<String, Object> toHitMap(Document doc) {
        Map<String, Object> hit = new LinkedHashMap<>(4);
        hit.put("content", doc.getMetadata().getContent().toString());
        hit.put("score", doc.getScore());
        hit.put("scored", doc.getScore() != null);
        hit.put("docId", doc.getMetadata().getDocId());
        return hit;
    }

    /**
     * 「检索不到」和「知识库坏了」必须分开：前者返回空数组，后者返回 503 + 明确原因。
     * 局部 handler 优先于全局 advice，避免被通用 500 / 404 兜底吞掉。
     */
    @ExceptionHandler(KnowledgeUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onKnowledgeUnavailable(KnowledgeUnavailableException e) {
        log.warn("[api] knowledge unavailable ({}): {}", e.reason(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("success", false, "code", e.reason().name(), "error", e.getMessage()));
    }

    /** 兼容 JSON 里 Boolean 与字符串两种写法；缺省或非法值一律按私有处理（安全默认）。 */
    private static boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        return raw != null && "true".equalsIgnoreCase(String.valueOf(raw).trim());
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
