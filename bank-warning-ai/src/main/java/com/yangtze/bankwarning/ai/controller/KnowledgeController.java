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
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 相邻 chunk 共享的边界字数，取值 {@code [0, MAX_CHUNK_SIZE / 2]}，默认 50。
     *
     * <p>固定长度切分会把一句话拦腰截断：「……判为高风险，应立｜即启动加密监测……」
     * 被切成两段后，任何一段单独看都缺少完整语义，检索命中哪段都答不全。
     * 重叠让相邻块共享边界文本，使被切断的句子至少在一块里保持完整。
     *
     * <p><b>定值方法</b>：业界不按绝对值定重叠，而是按块长的比例定，再按文档类型取档
     * ——通用文本 10% 左右，技术文档与代码 10~15%，长篇报告与法律文书 15~20%。
     * 本项目块长 500 字，文档以技术报告为主，取下限 {@code 500 × 10% = 50}。
     *
     * <p><b>真实语料上的核算</b>（9 万字技术报告，1252 段 / 217 块）：
     * <ul>
     *   <li>只有 4 段（占字数 2.8%）超过单块上限，即绝大多数切点落在段落边界上；</li>
     *   <li>块尾 112 处不是句末标点，其中 88% 是「1.1.1 研究目标」这类短标题（≤30 字），
     *       本身不需要补全；真正被切断的句子（残句 &gt;60 字）仅 9 条，其 p90 为 48 字；</li>
     *   <li>50 字可覆盖这 90% 的断句，代价是块数 +10%（217→239）、嵌入量 +12%。</li>
     * </ul>
     *
     * <p>不取 20%（100 字）的理由：块数增幅会到 +24%，而多出来的部分主要作用在
     * 「跨块上下文衔接」上——那属于答案忠实度的收益，需要生成层评估才能验证。
     * 在验证手段到位之前，默认值停在业界建议的下限。
     *
     * <p>上界是半块（250 字）：再大则下一块起点回落到上一块中部，产生大量近似重复的块。
     *
     * <p>为什么用字段注入而不是构造器参数：构造器已被 4 处测试直接 {@code new}，
     * 每加一个参数都要连带改一遍，而这里只是切分策略的一个标量。
     */
    @Value("${app.ai.rag.chunk-overlap:50}")
    private int chunkOverlap = 50;

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
     * 阶段二：一次入库的临时 doc_id。
     *
     * <p>为什么要临时 id：正式 doc_id 里可能已经躺着上一版，直接 INSERT 只会并排多一份
     * （主键 {@code id} 是随机 UUID，没有任何约束能挡住重复）。先写到一个不会与真实文档
     * 重名的 id 上，整批成功后再「删旧 + 换名」，失败就整批丢掉——旧版本始终可检索。
     *
     * <p>{@code #stg-} 这个形状是刻意的：真实文档名不会带它，检索时若真漏出一批 staging 行
     * 也能一眼看出是脏数据。doc_id 列是 VARCHAR(256)，长标题截断后仍留出 32 位余量。
     */
    static String stagingDocId(String docTitle) {
        String base = docTitle.length() > 200 ? docTitle.substring(0, 200) : docTitle;
        return base + "#stg-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /** 按统一口径造块：{@code chunkId} 用正式标题（换名后与检索去重键保持一致），docId 用临时 id。 */
    static List<Document> chunkDocuments(String docTitle, String stagingDocId,
                                         List<String> chunks, Map<String, String> payload) {
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            var builder = DocumentMetadata.builder()
                    .content(TextBlock.builder().text(chunks.get(i)).build())
                    .docId(stagingDocId)
                    .chunkId(docTitle + "_chunk_" + i)
                    .addPayload("chunkIndex", String.valueOf(i))
                    .addPayload("totalChunks", String.valueOf(chunks.size()));
            payload.forEach(builder::addPayload);
            docs.add(new Document(builder.build()));
        }
        return docs;
    }

    /**
     * 阶段二：把临时批次落成正式文档。
     *
     * <p><b>整批成功才换名</b>：只要有一段没嵌入成功（模型 400、内容审核、超时），
     * 换名就等于用残缺版本替换完整版本，「传一次丢一半」比「传失败」难查得多。
     * 所以这里宁可整批作废、旧版本留在库里，并在返回体里给出 {@code failed} 让前端说明原因。
     *
     * @return 本次入库是否生效
     */
    boolean finishIngest(String stagingDocId, String docTitle, Long userId,
                         KnowledgeService.IngestResult ingest) {
        if (ingest == null || ingest.total() == 0 || !ingest.allOk()) {
            knowledgeService.discardStaged(stagingDocId);
            log.warn("[api] 入库未完整（成功 {}/{} 段），本批作废，旧版本保持不变: docId={}",
                    ingest == null ? 0 : ingest.ok(), ingest == null ? 0 : ingest.total(), docTitle);
            return false;
        }
        knowledgeService.replaceStaged(stagingDocId, docTitle, userId);
        return true;
    }

    /**
     * 入库返回体。失败时<b>必须带 error</b>：前端写的是 {@code data.error || "上传失败: " + status}，
     * 只给一个 {@code success:false} 会让用户看到「上传失败: 200」——状态码是成功的意思，
     * 提示却什么都没有，比报错本身更难排查。
     */
    static Map<String, Object> ingestResponse(boolean ok, String docId, int chunks,
                                              KnowledgeService.IngestResult ingest,
                                              Map<String, ?> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", ok);
        body.put("docId", docId);
        body.put("chunks", chunks);
        body.put("embedded", ingest.ok());
        body.put("failed", ingest.failed());
        if (!ok) {
            body.put("error", "入库未完整：成功 " + ingest.ok() + "/" + ingest.total()
                    + " 段，本次改动未生效（库中仍是上一版），请稍后重试");
        }
        body.putAll(extra);
        return body;
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
        // 阶段二：先写进一个临时 doc_id，整批成功后再换名成正式 doc_id。
        // 直接往正式 doc_id 里 INSERT 会让「同文档再传一次」变成两份（id 是 UUID，
        // 表上没有任何东西能挡住重复），检索时两份相同内容互相挤名额。
        String staging = stagingDocId(title);
        List<Document> docs = chunkDocuments(title, staging, chunks, Map.of("type", type));
        Long userId = SecurityUtils.getCurrentUserId();
        KnowledgeService.IngestResult ingest = ingest(docs);
        boolean ok = finishIngest(staging, title, userId, ingest);
        // 默认私有；公共资料由上传界面显式勾选 isPublic=true。
        boolean isPublic = parseBoolean(body.get("isPublic"));
        // 只改刚换名进来的这批（user_id 仍为 NULL），不碰同 doc_id 下属于别人的行。
        if (ok && userId != null) {
            jdbcTemplate.update("UPDATE ai_knowledge_store SET user_id = ?, is_public = ? "
                    + "WHERE doc_id = ? AND user_id IS NULL", userId, isPublic, title);
        } else if (ok) {
            jdbcTemplate.update("UPDATE ai_knowledge_store SET is_public = ? "
                    + "WHERE doc_id = ? AND user_id IS NULL", isPublic, title);
        }
        // success 不再恒为 true：整批有一段没嵌入成功就不落库，前端必须能看见。
        return ingestResponse(ok, title, chunks.size(), ingest, Map.of("isPublic", isPublic));
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
            // 阶段二：同 importKnowledge，先写临时 doc_id，整批成功后再换名。
            String staging = stagingDocId(docTitle);
            List<Document> docs = chunkDocuments(docTitle, staging, chunks,
                    Map.of("type", type, "fileName", fileName));

            Long userId = SecurityUtils.getCurrentUserId();
            KnowledgeService.IngestResult ingest = ingest(docs);
            boolean ok = finishIngest(staging, docTitle, userId, ingest);
            // 阶段 2：公共标记与归属分开写，未登录时也要能把公共标记落库。
            // 只改刚换名进来的这批（user_id 仍为 NULL），不碰别人的同 doc_id 行。
            if (ok && userId != null) {
                jdbcTemplate.update("UPDATE ai_knowledge_store SET user_id = ?, is_public = ? "
                        + "WHERE doc_id = ? AND user_id IS NULL", userId, isPublic, docTitle);
            } else if (ok) {
                jdbcTemplate.update("UPDATE ai_knowledge_store SET is_public = ? "
                        + "WHERE doc_id = ? AND user_id IS NULL", isPublic, docTitle);
            }
            log.info("[api] knowledge imported, title={}, chunks={}, embedded={}, failed={}, file={}, isPublic={}",
                    docTitle, chunks.size(), ingest.ok(), ingest.failed(), fileName, isPublic);

            return ingestResponse(ok, docTitle, chunks.size(), ingest,
                    Map.of("type", type, "isPublic", isPublic));

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

    /**
     * 按空行切段、累加到 {@link #MAX_CHUNK_SIZE} 字为一块。
     *
     * <p>三条约束：
     * <ol>
     *   <li><b>单块不超过 {@link #MAX_CHUNK_SIZE}</b>：单段本身超长时先落盘已有块，
     *       再按句末把该段切碎。超长块既超出嵌入模型的有效语义范围，
     *       也会在检索时把整节内容稀释成一条低分命中；</li>
     *   <li><b>相邻块共享边界文本</b>（{@link #chunkOverlap}），
     *       避免一句话被拦腰截断后两段都答不全；</li>
     *   <li><b>不丢正文</b>：除重叠带来的重复，原文内容全部落在某个块里。</li>
     * </ol>
     *
     * <p>{@code chunkOverlap = 0} 时不加尾巴，块间无重复。
     */
    List<String> chunkText(String text) {
        int max = MAX_CHUNK_SIZE;
        // 重叠不能超过半块：否则下一块的起点会落回上一块中间，产生近乎重复的段。
        int overlap = Math.max(0, Math.min(chunkOverlap, max / 2));

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder current = new StringBuilder();

        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            // 单段本身就超长：先把已有的块落盘，再把这个长段按句子边界切碎。
            if (trimmed.length() > max) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitLongParagraph(trimmed, max, overlap));
                continue;
            }
            if (current.length() + trimmed.length() > max && current.length() > 0) {
                String body = current.toString().trim();
                chunks.add(body);
                String tail = overlapTail(body, overlap);
                // 尾巴加新段仍超上限（新段本身就很长）：宁可不要尾巴，也不突破单块长度。
                current = new StringBuilder(tail.length() + trimmed.length() > max ? "" : tail);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        if (chunks.isEmpty() && !text.isBlank()) {
            chunks.add(text.trim());
        }
        return chunks;
    }

    /**
     * 把超长段落切成不超过 {@code max} 的若干块，尽量落在句末，避免从词中间截断。
     *
     * <p>找不到句末标点（例如无标点的长数字串、英文表格）时退化为硬切——
     * 有截断总比整段超长好，因为超长段会同时拖累嵌入质量和检索精度。
     */
    private static List<String> splitLongParagraph(String paragraph, int max, int overlap) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(paragraph.length(), start + max);
            if (end < paragraph.length()) {
                int cut = lastSentenceEnd(paragraph, start, end);
                if (cut > start) {
                    end = cut;
                }
            }
            String piece = paragraph.substring(start, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (end >= paragraph.length()) {
                break;
            }
            // 至少前进一个字，防止 overlap 恰好等于块长时死循环
            start = Math.max(start + 1, end - overlap);
        }
        return out;
    }

    /** 在 [from, to) 里找最后一个句末标点之后的切点；找不到返回 -1。 */
    private static int lastSentenceEnd(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 取上一块末尾的重叠文本，长度落在 {@code [overlap / 2, overlap]}；{@code overlap == 0} 时返回空串。
     *
     * <p>优先让尾巴从句子边界开始，避免下一块以半句话开头。但「对齐」不能以牺牲重叠量为代价：
     * 若窗口里第一个句末标点太靠后（对齐后剩余不足 {@code overlap / 2}），说明真正的句界
     * 落在窗口之外，此时退回原始窗口——宁可尾巴从半句开始，也不能把重叠让没了。
     *
     * <p>这一条在段落末尾是短标题时尤为关键：例如上一块以「…具有节点的作用。\n\n2.1.3.2 主要地质问题」
     * 结尾、配置 100 字时，若对齐到该标题只继承 14 字，而标题并不携带上一块的正文信息，
     * 重叠形同未生效。
     */
    private static String overlapTail(String body, int overlap) {
        if (overlap <= 0 || body.isEmpty()) {
            return "";
        }
        // 块本身不比窗口长：整块复制给下一块是重复，不是重叠。
        // 不排除这两种情况会让短块（标题行、单句段）在原处和下一块开头各出现一次，
        // 并且下一块 = 整块 + 新段，突破单块上限。
        if (body.length() <= overlap) {
            return "";
        }
        int hard = Math.max(0, body.length() - overlap);
        int minKeep = overlap / 2;
        for (int i = hard; i < body.length() - 1; i++) {
            char c = body.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                // 对齐后仍须保留至少一半重叠量，否则放弃对齐
                if (body.length() - (i + 1) >= minKeep) {
                    return body.substring(i + 1).trim();
                }
                break;
            }
        }
        return body.substring(hard).trim();
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
