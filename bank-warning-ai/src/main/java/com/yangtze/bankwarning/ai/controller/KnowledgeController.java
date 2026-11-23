package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.KnowledgeUnavailableException;
import com.yangtze.bankwarning.ai.service.MdLiteChunker;
import com.yangtze.bankwarning.ai.service.PdfService;
import com.yangtze.bankwarning.ai.service.SourceCitation;
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

    /** 文本直传（{@code POST /knowledge}）没有经过任何提取器。 */
    static final String EXTRACTOR_INLINE = "inline";

    /** PDF 结构化提取，保留版面与表格。 */
    static final String EXTRACTOR_DOCLING = "docling";

    /** PDF 纯文本兜底，只回正文，表格与层级都会丢。 */
    static final String EXTRACTOR_PYPDF = "pypdf";

    /** DOCX 结构化提取（body 顺序 + 表格 + 字面编号推层级）。 */
    static final String EXTRACTOR_DOCX = "docx-structure";

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

    /**
     * 按统一口径造块，并把结构元信息写进 payload。
     *
     * <p>{@code headingPath} / {@code page} / {@code blockType} 刻意放 payload 而不是
     * {@code content}：它们要能被程序读到（拼引用标签、按章节过滤），但不该被向量化，
     * 也不该占提示词的 token。{@code payload} 是 JSONB，加键不需要 DDL。
     *
     * <p>空值不写键：{@code headingPath} 为空说明这段不属于任何标题（纯文本或文档前言），
     * 写一个空串会让「有没有归属」变成两义；{@code page == 0} 表示未知（docx 没有页概念），
     * 与「第 0 页」区分开。
     */
    static List<Document> structuredDocuments(String docTitle, String stagingDocId,
                                              List<MdLiteChunker.Chunk> chunks,
                                              Map<String, String> payload) {
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            MdLiteChunker.Chunk chunk = chunks.get(i);
            var builder = DocumentMetadata.builder()
                    .content(TextBlock.builder().text(chunk.text()).build())
                    .docId(stagingDocId)
                    .chunkId(docTitle + "_chunk_" + i)
                    .addPayload("chunkIndex", String.valueOf(i))
                    .addPayload("totalChunks", String.valueOf(chunks.size()))
                    .addPayload("blockType", chunk.blockType());
            if (chunk.headingPath() != null && !chunk.headingPath().isBlank()) {
                builder.addPayload("headingPath", chunk.headingPath());
            }
            if (chunk.page() > 0) {
                builder.addPayload("page", String.valueOf(chunk.page()));
            }
            payload.forEach(builder::addPayload);
            docs.add(new Document(builder.build()));
        }
        return docs;
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
        // 阶段 3：换名成功后补写 search_text（jieba 词项）。刻意放在事务外——
        // 这一列只服务中文关键词路，写不进去也不该让入库失败；失败只打 warn，
        // 该篇暂时只走向量路，运维可再调 refreshSearchText 补。
        knowledgeService.refreshSearchText(docTitle);
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
        // 文本直传没有提取器：extractor 记为 inline，不产生 headingPath 与页码。
        List<MdLiteChunker.Chunk> chunks = chunkStructured(content);
        // 阶段二：先写进一个临时 doc_id，整批成功后再换名成正式 doc_id。
        // 直接往正式 doc_id 里 INSERT 会让「同文档再传一次」变成两份（id 是 UUID，
        // 表上没有任何东西能挡住重复），检索时两份相同内容互相挤名额。
        String staging = stagingDocId(title);
        List<Document> docs = structuredDocuments(title, staging, chunks,
                Map.of("type", type, "extractor", EXTRACTOR_INLINE));
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
        String suffix;
        if (lowerName.endsWith(".pdf")) {
            suffix = ".pdf";
        } else if (lowerName.endsWith(".docx")) {
            suffix = ".docx";
        } else {
            return Map.of("success", false, "error", "仅支持 PDF 和 DOCX 文件");
        }

        String docTitle = (title != null && !title.isBlank()) ? title : fileName;

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("knowledge-", suffix);
            file.transferTo(tempFile.toFile());

            Extraction extraction = extract(tempFile, suffix);
            if (!extraction.ok()) {
                return Map.of("success", false, "error", extraction.error());
            }
            String text = extraction.text();
            if (text == null || text.isBlank()) {
                return Map.of("success", false, "error", "文件中未提取到文本内容");
            }

            List<MdLiteChunker.Chunk> chunks = chunkStructured(text);
            // 阶段二：同 importKnowledge，先写临时 doc_id，整批成功后再换名。
            String staging = stagingDocId(docTitle);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("fileName", fileName);
            payload.put("extractor", extraction.extractor());
            payload.put("format", MdLiteChunker.FORMAT);
            // 降级是个事实，不是内部细节：pypdf 只回纯文本，表格与层级都会丢，
            // 检索不命中时得能分清「资料里没有」和「抽取时就没拿到」。
            payload.put("degraded", String.valueOf(extraction.degraded()));
            List<Document> docs = structuredDocuments(docTitle, staging, chunks, payload);

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
            log.info("[api] knowledge imported, title={}, chunks={}, embedded={}, failed={}, file={}, isPublic={}, extractor={}, degraded={}",
                    docTitle, chunks.size(), ingest.ok(), ingest.failed(), fileName, isPublic,
                    extraction.extractor(), extraction.degraded());

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("type", type);
            extra.put("isPublic", isPublic);
            extra.put("extractor", extraction.extractor());
            extra.put("degraded", extraction.degraded());
            return ingestResponse(ok, docTitle, chunks.size(), ingest, extra);

        } catch (Exception e) {
            log.error("[api] upload failed", e);
            return Map.of("success", false, "error", "导入失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 按后缀选提取器：PDF 先试 Docling，拿不到再回落 pypdf。
     *
     * <p><b>为什么降级必须留下痕迹：</b>pypdf 只回纯文本，表格与标题层级全部丢失。
     * 不标出来的话，「检索没命中」和「入库时就没拿到」在日志里长得一模一样，
     * 而两者的处置完全相反——前者去调排序，后者要换提取器。
     *
     * <p><b>Docling 失败是常态不是异常：</b>模型目录没预置、离线机器首次转换、
     * 脚本还没物化到 {@code .skills-cache}，都会让它拿不到结果。这些都不该让上传失败。
     */
    private Extraction extract(Path file, String suffix) {
        if (".pdf".equals(suffix)) {
            Map<String, Object> docling = pdfService.processPdf(
                    "pdf", "extract_text_docling.py", file.toString());
            String text = textOf(docling);
            if (text != null && !text.isBlank()) {
                return new Extraction(text, EXTRACTOR_DOCLING, false, null);
            }
            log.warn("[api] Docling 抽取不可用，回落 pypdf（表格与层级会丢失）: {}", errorOf(docling));
            Map<String, Object> plain = pdfService.processPdf(
                    "pdf", "extract_text.py", file.toString());
            String fallback = textOf(plain);
            if (fallback == null) {
                return new Extraction(null, EXTRACTOR_PYPDF, true, errorOf(plain));
            }
            return new Extraction(fallback, EXTRACTOR_PYPDF, true, null);
        }

        Map<String, Object> result = pdfService.processPdf(
                "word", "extract_text_docx.py", file.toString());
        String text = textOf(result);
        if (text == null) {
            return new Extraction(null, EXTRACTOR_DOCX, false, errorOf(result));
        }
        return new Extraction(text, EXTRACTOR_DOCX, false, null);
    }

    /** 成功结果的正文；执行失败返回 null（空正文照常返回空串，交由调用方给统一文案）。 */
    private static String textOf(Map<String, Object> result) {
        if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
            return null;
        }
        Object content = result.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    private static String errorOf(Map<String, Object> result) {
        Object error = result == null ? null : result.get("error");
        return error == null ? "提取器未返回结果" : String.valueOf(error);
    }

    /**
     * 一次文件抽取的结果。
     *
     * @param text      正文；抽取器执行失败时为 null
     * @param extractor 实际生效的提取器，写进 payload 供事后排查
     * @param degraded  是否走了有损降级路径
     * @param error     失败原因；成功为 null
     */
    record Extraction(String text, String extractor, boolean degraded, String error) {
        boolean ok() {
            return error == null;
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
                // 抽取降级要在列表里看得见：pypdf 拿不到表格与层级，
                // 用户问「为什么表里的数搜不到」时，答案就在这一行。
                meta.putIfAbsent("extractor", p.getOrDefault("extractor", ""));
                meta.putIfAbsent("degraded", "true".equals(String.valueOf(p.getOrDefault("degraded", ""))));
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
        Map<String, Object> hit = new LinkedHashMap<>(7);
        hit.put("content", doc.getMetadata().getContent().toString());
        hit.put("score", doc.getScore());
        hit.put("scored", doc.getScore() != null);
        hit.put("docId", doc.getMetadata().getDocId());
        // 阶段 4：搜索面板也要能显示「第 k/n 段」。未知时给 null 而不是 0——
        // 0 会被前端当成「第一段」，比缺字段更容易误导。
        int no = SourceCitation.chunkNo(doc);
        int total = SourceCitation.totalChunks(doc);
        hit.put("chunkIndex", no < 0 ? null : no);
        hit.put("totalChunks", total < 0 ? null : total);
        hit.put("sourceLabel", SourceCitation.label(doc));
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
     *
     * <p><b>实现已搬到 {@link MdLiteChunker#chunkPlain}</b>，这里只剩一层薄委托：
     * 上传链路现在走 {@link #chunkStructured}，这个入口保留给「纯文本、无结构」的场景
     * 与既有的切分不变量测试——它们钉的是同一套算法，不该因为换了个调用方就失去守门。
     */
    List<String> chunkText(String text) {
        return MdLiteChunker.chunkPlain(text, MAX_CHUNK_SIZE, chunkOverlap);
    }

    /**
     * 结构感知切块：md-lite 输入按标题分节、表格原子成块；纯文本输入与
     * {@link #chunkText} 逐字一致，所以「升级提取器」不会顺带改变纯文本的切法。
     *
     * <p>切块逻辑放在 {@link MdLiteChunker} 而不是留在这里：控制器里只能靠端到端测，
     * 一旦块数或归属出问题，定位不到「是切块错了还是嵌入丢了」。
     */
    List<MdLiteChunker.Chunk> chunkStructured(String text) {
        return MdLiteChunker.chunk(text, MAX_CHUNK_SIZE, chunkOverlap);
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
