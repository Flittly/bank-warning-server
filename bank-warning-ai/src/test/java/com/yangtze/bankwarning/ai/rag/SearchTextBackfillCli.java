package com.yangtze.bankwarning.ai.rag;

import com.yangtze.bankwarning.ai.service.ChineseTokenizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 3 的存量回填工具：给 {@code ai_knowledge_store.search_text} 补上 jieba 词项。
 *
 * <p><b>为什么必须有它</b>：{@code search_text} 由 Java 侧分词产生（PostgreSQL 没有
 * jieba 词典），SQL 补不出来。V19 只加列与索引，历史行在回填前 {@code search_text}
 * 为 NULL —— 这些行不参与中文关键词路（{@code @@} 遇 NULL 判 false），但仍能被向量路
 * 与 ASCII 锚点路召回，不会「凭空消失」。所以回填是「补一条召回通道」，不是修数据损坏。
 *
 * <p><b>刻意不叫 {@code *Test}</b>：返回码只用于手动运行，Surefire 不收集它。
 * 幂等，可反复跑；{@code --dry-run} 只统计不写库。
 *
 * <pre>
 * mvn -pl bank-warning-ai -am test-compile
 * java -cp "&lt;classes;test-classes;deps&gt;" \
 *      com.yangtze.bankwarning.ai.rag.SearchTextBackfillCli --dry-run
 * java -cp ... com.yangtze.bankwarning.ai.rag.SearchTextBackfillCli
 * </pre>
 *
 * <p>可选参数：{@code --doc <doc_id>} 只刷一篇；{@code --jdbc-url} / {@code --db-user} /
 * {@code --db-password} 覆盖连接（默认与本机开发库一致）。
 */
public final class SearchTextBackfillCli {

    /**
     * 正文表达式，必须与 {@code SparseRetriever.TEXT_EXPR} 逐字一致
     * （那边是包级可见，本类在 rag 包下取不到，故重复一次）。
     */
    private static final String TEXT_EXPR = "(content::jsonb ->> 'text')";

    private SearchTextBackfillCli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("[backfill] 失败：" + e);
            System.exit(1);
        }
    }

    static int run(String[] args) {
        Map<String, String> opts = parse(args);
        boolean dryRun = opts.containsKey("dry-run");
        String onlyDoc = opts.get("doc");

        String url = opts.getOrDefault("jdbc-url", "jdbc:postgresql://localhost:5432/bank_risk_db");
        String user = opts.getOrDefault("db-user", "postgres");
        String password = opts.getOrDefault("db-password", "123456");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));

        List<String> docs = onlyDoc != null
                ? List.of(onlyDoc)
                : jdbc.queryForList(
                        "SELECT DISTINCT doc_id FROM ai_knowledge_store WHERE doc_id IS NOT NULL "
                                + "ORDER BY doc_id", String.class);

        System.out.printf("[backfill] 目标文档 %d 篇，模式=%s%n",
                docs.size(), dryRun ? "dry-run（不写库）" : "写库");
        int totalRows = 0;
        int totalUpdated = 0;
        int totalSkipped = 0;
        long startAll = System.currentTimeMillis();

        for (String docId : docs) {
            long start = System.currentTimeMillis();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, " + TEXT_EXPR + " AS body FROM ai_knowledge_store WHERE doc_id = ?",
                    docId);
            List<Object[]> batch = new ArrayList<>(rows.size());
            int skipped = 0;
            int termSum = 0;
            for (Map<String, Object> row : rows) {
                Object body = row.get("body");
                String searchText = ChineseTokenizer.indexText(body == null ? null : body.toString());
                if (searchText.isEmpty()) {
                    skipped++;
                    continue;
                }
                termSum += searchText.split(" ").length;
                batch.add(new Object[]{searchText, row.get("id")});
            }
            if (!dryRun && !batch.isEmpty()) {
                jdbc.batchUpdate("UPDATE ai_knowledge_store SET search_text = ? WHERE id = ?", batch);
            }
            totalRows += rows.size();
            totalUpdated += batch.size();
            totalSkipped += skipped;
            System.out.printf("[backfill] %-28s 段=%-5d 词项均值=%-6.1f 空正文=%-3d 耗时=%dms%n",
                    docId, rows.size(),
                    batch.isEmpty() ? 0.0 : (double) termSum / batch.size(),
                    skipped, System.currentTimeMillis() - start);
        }

        System.out.printf("[backfill] SUMMARY 段=%d 已写=%d 跳过=%d 总耗时=%dms%n",
                totalRows, totalUpdated, totalSkipped, System.currentTimeMillis() - startAll);
        return 0;
    }

    /** 极简参数解析：{@code --k v} 或 {@code --flag}，与 RagEvalCli 同风格。 */
    static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(key, args[++i]);
            } else {
                opts.put(key, "true");
            }
        }
        return opts;
    }
}
