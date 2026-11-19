package com.yangtze.bankwarning.ai.rag;

import com.yangtze.bankwarning.ai.config.ChineseKeywordProperties;
import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.QueryRewriteProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.config.RagThresholdProperties;
import com.yangtze.bankwarning.ai.config.RerankProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.BatchEmbeddingClient;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.RerankClient;
import com.yangtze.bankwarning.ai.service.SourceCitation;
import com.yangtze.bankwarning.ai.service.SqlPermissionVectorSearch;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.store.PgVectorStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阶段 5：引用可回查性审计（离线，手动跑）。
 *
 * <p><b>它回答的问题</b>：回答里那句「[1] 来源：X.docx · 第 3/239 段」，真的能点回到原文第 3 段吗？
 * 还是只是一串看起来很像出处的字符？阶段 4 把「来源」交给了模型，但<b>没有验证过它是否可回查</b>——
 * 引用一旦不可回查，比没有引用更糟：它让人以为已经核对过了。
 *
 * <p><b>只做机器能确定的那一半</b>：段号是否落在 1..总段数、doc_id + chunk_id 是否真能在库里定位到行、
 * 渲染出的标签是否非空。至于「这一段是否支持这句话」，需要读懂语义，属于人工/模型评审，
 * 报告里留一列空勾选框，不假装自动判定。
 *
 * <p><b>刻意不叫 {@code *Test}</b>：它要 key、要网络、要真库，不进 {@code mvn test}。
 *
 * <pre>
 * mvn -pl bank-warning-ai -am test-compile
 * java -cp "&lt;classes;test-classes;deps&gt;" \
 *      com.yangtze.bankwarning.ai.rag.CiteAuditCli --jdbc --limit 10 \
 *      --out reports/citation-audit.md
 * </pre>
 */
public final class CiteAuditCli {

    private CiteAuditCli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("[cite-audit] 失败：" + e);
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        Map<String, String> opts = RagEvalCli.parse(args);
        int limit = intOpt(opts, "limit", 10);
        int topK = intOpt(opts, "top-k", 5);
        String out = opts.getOrDefault("out", "reports/citation-audit-" + LocalDate.now() + ".md");

        String url = opts.getOrDefault("jdbc-url", "jdbc:postgresql://localhost:5432/bank_risk_db");
        String user = opts.getOrDefault("db-user", "postgres");
        String password = opts.getOrDefault("db-password", "123456");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String key = RagEvalCli.resolveDashScopeKey(opts);

        RagProperties props = new RagProperties(
                true, true, true, false, 5, 5, 5, 0.4, 5, 15_000L, 600_000L);
        VDBStoreBase store = PgVectorStore.builder()
                .jdbcUrl(url).username(user).password(password)
                .schema("public").tableName("ai_knowledge_store")
                .dimensions(1024).distanceType(PgVectorStore.DistanceType.COSINE)
                .build();
        // 与线上默认档一致；精排关掉以省一次外部调用——审计的是引用，不是排序质量。
        KnowledgeService service = new KnowledgeService(
                DashScopeTextEmbedding.builder().apiKey(key)
                        .modelName("text-embedding-v4").dimensions(1024).build(),
                store, jdbc, new JdbcRagTraceStore(jdbc, props), props,
                HybridRagProperties.enabledWithDefaults(),
                new SqlPermissionVectorSearch(jdbc, true),
                BatchEmbeddingClient.disabled(EmbeddingBatchProperties.disabled()),
                RerankClient.disabled(RerankProperties.disabled()),
                ChineseKeywordProperties.enabledDefaults(),
                new RagThresholdProperties(0.0),
                QueryRewriteProperties.disabled());

        List<Row> rows = new ArrayList<>();
        int picked = 0;
        for (RagGoldenSet.GoldenCase c : RagGoldenSet.load().cases) {
            if (picked >= limit) {
                break;
            }
            // 只挑「应当命中某篇」的题：拒答题本来就不该有引用，混进来只会稀释分母。
            if (!c.scored || c.mustRefuse || c.expectedDocIdsOrEmpty().isEmpty()) {
                continue;
            }
            picked++;
            List<Document> docs = service.retrieve(
                    c.question, KnowledgeService.Scenario.SEARCH, AiCaller.forAdmin(), topK);
            rows.add(new Row(c, docs, jdbc));
        }

        String report = render(rows);
        Path path = Path.of(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, report, StandardCharsets.UTF_8);

        long total = rows.stream().mapToLong(r -> r.cites.size()).sum();
        long locatable = rows.stream().mapToLong(r -> r.cites.stream().filter(Cite::locatable).count()).sum();
        System.out.printf("[cite-audit] 题数=%d 引用条数=%d 可回查=%d (%.2f%%)%n",
                rows.size(), total, locatable, total == 0 ? 0 : 100.0 * locatable / total);
        System.out.println("[cite-audit] 报告: " + path.toAbsolutePath());
        System.out.println("[cite-audit] 报告里「是否支持」一列需人工勾选，工具不做语义判定。");
        return 0;
    }

    /** 单条引用：标签、段号、以及「库里能不能定位到这一行」。 */
    record Cite(String label, int chunkNo, int totalChunks, boolean rowExists) {
        boolean locatable() {
            if (label == null || label.isBlank()) {
                return false;
            }
            boolean indexSane = totalChunks <= 0 || (chunkNo >= 1 && chunkNo <= totalChunks);
            return indexSane && rowExists;
        }
    }

    record Row(RagGoldenSet.GoldenCase c, List<Document> docs, List<Cite> cites) {
        Row(RagGoldenSet.GoldenCase c, List<Document> docs, JdbcTemplate jdbc) {
            this(c, docs, docs.stream().map(d -> toCite(d, jdbc)).toList());
        }
    }

    private static Cite toCite(Document doc, JdbcTemplate jdbc) {
        String docId = SourceCitation.docId(doc);
        // 库里那一列叫 chunk_id，对应 metadata.chunkId；Document.getId() 是 SDK 自己生成的对象 id，
        // 拿它去查一定查不到（第一版就是这么写的，结果 50/50 条全部「不可回查」）。
        String chunkId = doc.getMetadata() == null ? null : doc.getMetadata().getChunkId();
        Integer n = (chunkId == null || chunkId.isBlank()) ? 0 : jdbc.queryForObject(
                "SELECT count(*) FROM ai_knowledge_store WHERE doc_id = ? AND chunk_id = ?",
                Integer.class, docId, chunkId);
        return new Cite(SourceCitation.label(doc), SourceCitation.chunkNo(doc),
                SourceCitation.totalChunks(doc), n != null && n > 0);
    }

    private static String render(List<Row> rows) {
        StringBuilder md = new StringBuilder(4096);
        md.append("# 引用可回查性审计\n\n");
        md.append("> 用途：人工抽检的底稿。**工具只验证「能不能定位到原文」，不判断「原文是否支持这句话」。**\n\n");
        md.append("## 汇总\n\n");
        long total = rows.stream().mapToLong(r -> r.cites.size()).sum();
        long locatable = rows.stream().mapToLong(r -> r.cites.stream().filter(Cite::locatable).count()).sum();
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 题数 | ").append(rows.size()).append(" |\n");
        md.append("| 引用条数 | ").append(total).append(" |\n");
        md.append("| 可回查条数 | ").append(locatable).append(" |\n");
        md.append("| 可回查率 | ")
                .append(total == 0 ? "—" : String.format("%.2f%%", 100.0 * locatable / total))
                .append(" |\n\n");
        md.append("判据：可回查率应为 100%；不为 100% 说明 label 与 payload 段号不一致，")
                .append("或 chunk 已被重新入库替换。\n\n");
        md.append("## 逐题明细\n\n");
        md.append("| 题号 | 问题 | 引用 | 段号 | 段号自洽 | 库中可定位 | 原文前 80 字 | 该段是否支持回答 |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            if (r.cites.isEmpty()) {
                md.append("| ").append(r.c.id).append(" | ").append(escape(r.c.question))
                        .append(" | 0 条 | — | — | — | （未召回到任何段落） | ☐ |\n");
                continue;
            }
            for (int i = 0; i < r.cites.size(); i++) {
                Cite cite = r.cites.get(i);
                String body = i < r.docs.size() ? snippet(r.docs.get(i)) : "";
                md.append("| ").append(i == 0 ? r.c.id : "")
                        .append(" | ").append(i == 0 ? escape(r.c.question) : "")
                        .append(" | [").append(i + 1).append("] ").append(escape(cite.label()))
                        .append(" | ").append(cite.chunkNo() < 0 ? "?" : String.valueOf(cite.chunkNo()))
                        .append(" | ").append(cite.totalChunks() <= 0 ? "无分母"
                                : (cite.chunkNo() >= 1 && cite.chunkNo() <= cite.totalChunks() ? "是" : "否"))
                        .append(" | ").append(cite.rowExists() ? "是" : "否")
                        .append(" | ").append(escape(body))
                        .append(" | ☐ |\n");
            }
        }
        return md.toString();
    }

    private static String snippet(Document doc) {
        String text = doc.getMetadata().getContent() == null
                ? "" : doc.getMetadata().getContent().toString();
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static int intOpt(Map<String, String> opts, String key, int fallback) {
        try {
            String v = opts.get(key);
            return v == null || v.isBlank() || "true".equals(v) ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
