package com.yangtze.bankwarning.ai.rag;

import com.yangtze.bankwarning.ai.config.ChineseKeywordProperties;
import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.QueryRewriteProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.SparseRetriever;
import io.agentscope.core.rag.model.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 阶段 3 的<b>单路对照</b>：只跑关键词路（不碰 embedding、不联网），对比
 * 「纯 ASCII 锚点」与「锚点 + 中文词项」在同一份考卷上的召回差异。
 *
 * <p><b>为什么要单独有它</b>：{@link RagEvalCli} 的混合检索对照需要真实 embedding，
 * 而 embedding 是外部依赖（账号额度、网络、限流）。中文词项只影响第二路，
 * 用单路对照就能把「阶段 3 带来多少增益」和「向量路好不好」分开计量；
 * 换机器、断网、账号欠费时也能复现。
 *
 * <p><b>只看文档级指标</b>（Recall@K / MRR），与 {@link RagRetrievalEvaluator} 同口径：
 * 考卷标注的是 docId，块级命中没有可比基准。
 *
 * <pre>
 * mvn -pl bank-warning-ai -am test-compile
 * java -cp "&lt;classes;test-classes;deps&gt;" \
 *      com.yangtze.bankwarning.ai.rag.SparseOnlyEvalCli --out reports/rag-eval-p3-sparse.md
 * </pre>
 */
public final class SparseOnlyEvalCli {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SparseOnlyEvalCli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("[sparse-eval] 失败：" + e);
            System.exit(1);
        }
    }

    static int run(String[] args) throws IOException {
        Map<String, String> opts = parse(args);
        int topK = intOpt(opts, "top-k", 5);
        String out = opts.getOrDefault("out", "reports/rag-eval-p3-sparse.md");

        String url = opts.getOrDefault("jdbc-url", "jdbc:postgresql://localhost:5432/bank_risk_db");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                url, opts.getOrDefault("db-user", "postgres"),
                opts.getOrDefault("db-password", "123456")));

        RagProperties props = new RagProperties(
                true, true, true, false, 5, 5, 5, 0.4, 5, 15_000L, 600_000L);
        HybridRagProperties hybrid = HybridRagProperties.enabledWithDefaults();
        // 阶段 5：--rewrite 在「+中文词项」的基础上再叠一层归一化与同义词扩展。
        // 基线（锚点 only）始终保持纯净：它代表改造前的能力，不能被任何新开关污染。
        QueryRewriteProperties rewriteProps = opts.containsKey("rewrite")
                ? QueryRewriteProperties.enabledDefaults()
                : QueryRewriteProperties.disabled();
        SparseRetriever anchorsOnly = new SparseRetriever(jdbc, props, hybrid,
                ChineseKeywordProperties.disabled());
        SparseRetriever withChinese = new SparseRetriever(jdbc, props, hybrid,
                ChineseKeywordProperties.enabledDefaults(), rewriteProps);

        RagGoldenSet golden = RagGoldenSet.load();
        List<Row> rows = new ArrayList<>(golden.cases.size());
        for (RagGoldenSet.GoldenCase c : golden.cases) {
            List<String> before = docIds(anchorsOnly.retrieve(c.question, caller(c), topK));
            List<String> after = docIds(withChinese.retrieve(c.question, caller(c), topK));
            rows.add(new Row(c, before, after));
        }

        String report = render(rows, golden, topK, rewriteProps.enabled);
        Path path = Path.of(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, report, StandardCharsets.UTF_8);

        Summary before = summarize(rows, false);
        Summary after = summarize(rows, true);
        System.out.printf("[sparse-eval] 锚点only 命中=%d/%d Recall=%.4f MRR=%.4f%n",
                before.hitCases, before.total, before.recall, before.mrr);
        System.out.printf("[sparse-eval] %s 命中=%d/%d Recall=%.4f MRR=%.4f%n",
                rewriteProps.enabled ? "+中文词项+改写" : "+中文词项",
                after.hitCases, after.total, after.recall, after.mrr);
        System.out.println("[sparse-eval] 报告: " + path.toAbsolutePath());
        return 0;
    }

    private static AiCaller caller(RagGoldenSet.GoldenCase c) {
        return "user".equals(c.caller) ? AiCaller.forUser(3L) : AiCaller.forAdmin();
    }

    private static List<String> docIds(List<Document> docs) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Document d : docs) {
            String id = d.getMetadata().getDocId();
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    // ---------------- 指标 ----------------

    static final class Row {
        final RagGoldenSet.GoldenCase c;
        final List<String> before;
        final List<String> after;

        Row(RagGoldenSet.GoldenCase c, List<String> before, List<String> after) {
            this.c = c;
            this.before = before;
            this.after = after;
        }

        boolean counted() {
            return c.scored && !c.mustRefuse;
        }

        List<String> got(boolean chinese) {
            return chinese ? after : before;
        }
    }

    static final class Summary {
        int total;
        int hitCases;
        double recall;
        double mrr;
    }

    /** 只统计「计分且非拒答」的题；拒答题的期望是空集，混进来会让指标没有意义。 */
    static Summary summarize(List<Row> rows, boolean chinese) {
        Summary s = new Summary();
        double recallSum = 0;
        double mrrSum = 0;
        for (Row r : rows) {
            if (!r.counted()) {
                continue;
            }
            s.total++;
            List<String> got = r.got(chinese);
            List<String> expected = r.c.expectedDocIdsOrEmpty();
            double rec = recall(got, expected);
            double m = mrr(got, expected);
            if (m > 0) {
                s.hitCases++;
            }
            recallSum += rec;
            mrrSum += m;
        }
        s.recall = s.total == 0 ? 0 : recallSum / s.total;
        s.mrr = s.total == 0 ? 0 : mrrSum / s.total;
        return s;
    }

    static double recall(List<String> got, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 0;
        }
        long hit = expected.stream().filter(got::contains).count();
        return (double) hit / expected.size();
    }

    /** 文档去重后第一个命中的位次的倒数（第 1 位 1.0，第 3 位 0.33）。 */
    static double mrr(List<String> got, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 0;
        }
        int rank = 1;
        for (String docId : got) {
            if (expected.contains(docId)) {
                return 1.0 / rank;
            }
            rank++;
        }
        return 0;
    }

    // ---------------- 报告 ----------------

    static String render(List<Row> rows, RagGoldenSet golden, int topK, boolean rewrite) {
        Summary before = summarize(rows, false);
        Summary after = summarize(rows, true);
        StringBuilder sb = new StringBuilder(4096);
        sb.append(rewrite ? "# 关键词路单路对照（中文词项 + 查询改写）\n\n"
                : "# 关键词路单路对照（阶段 3：中文词项）\n\n");
        sb.append("- 生成时间：").append(LocalDateTime.now().format(TS)).append('\n');
        sb.append("- 考卷：v").append(golden.version).append("，共 ").append(rows.size())
                .append(" 题，计分且非拒答 ").append(before.total).append(" 题\n");
        sb.append("- 口径：只跑 SparseRetriever（不调 embedding、不联网），topK=")
                .append(topK).append("，指标按 docId 计算\n");
        sb.append("- 对照：`锚点only` = 阶段 3 之前的行为（只认 ASCII 标识符与数字）；`")
                .append(rewrite ? "+中文词项+改写` = 阶段 3 + 归一化与同义词扩展" : "+中文词项` = 阶段 3（jieba + tsvector）")
                .append("`\n\n");

        sb.append("## 汇总\n\n");
        sb.append("| 模式 | 有命中题数 | Recall@").append(topK).append(" | MRR |\n");
        sb.append("|---|---|---|---|\n");
        sb.append(row("锚点only（阶段 3 之前）", before));
        sb.append(row(rewrite ? "+中文词项+改写" : "+中文词项（阶段 3）", after));
        sb.append('\n');

        sb.append("## 分类\n\n");
        sb.append("| 类别 | 题数 | 锚点only 命中 | 中文 命中 | 锚点only Recall | 中文 Recall | ΔRecall |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (String cat : List.of("A", "B", "C", "D")) {
            List<Row> sub = rows.stream().filter(r -> cat.equals(r.c.category)).toList();
            Summary b = summarize(sub, false);
            Summary a = summarize(sub, true);
            if (b.total == 0) {
                continue;
            }
            sb.append("| ").append(cat).append(" | ").append(b.total)
                    .append(" | ").append(b.hitCases).append(" | ").append(a.hitCases)
                    .append(String.format(" | %.4f | %.4f | %+.4f |%n", b.recall, a.recall, a.recall - b.recall));
        }
        sb.append('\n');

        sb.append("## 逐题\n\n");
        sb.append("| id | 类别 | 计分 | 期望 | 锚点only 返回 | ")
                .append(rewrite ? "中文+改写 返回" : "中文返回").append(" | 变化 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            String mark = r.counted() ? "是" : "否";
            sb.append("| ").append(r.c.id).append(" | ").append(r.c.category)
                    .append(" | ").append(mark)
                    .append(" | ").append(String.join(",", r.c.expectedDocIdsOrEmpty()))
                    .append(" | ").append(String.join(",", r.before))
                    .append(" | ").append(String.join(",", r.after))
                    .append(" | ").append(change(r)).append(" |\n");
        }
        return sb.toString();
    }

    private static String row(String label, Summary s) {
        return String.format("| %s | %d/%d | %.4f | %.4f |%n",
                label, s.hitCases, s.total, s.recall, s.mrr);
    }

    /** 只标注「由未命中变命中 / 反之」，逐题看差异时不必逐行比字符串。 */
    private static String change(Row r) {
        boolean beforeHit = !r.before.isEmpty();
        boolean afterHit = !r.after.isEmpty();
        if (!beforeHit && afterHit) {
            return "新增召回";
        }
        if (beforeHit && !afterHit) {
            return "退化为空";
        }
        return beforeHit ? "—" : "仍为空";
    }

    static int intOpt(Map<String, String> opts, String key, int def) {
        try {
            return opts.containsKey(key) ? Integer.parseInt(opts.get(key)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(key, args[++i]);
            } else {
                opts.put(key, "true");
            }
        }
        return opts;
    }
}
