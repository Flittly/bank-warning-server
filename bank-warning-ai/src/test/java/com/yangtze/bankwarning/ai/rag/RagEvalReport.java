package com.yangtze.bankwarning.ai.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段 4 的产出物：一份「同一批题、改动前后各跑一次」的对照报告。
 *
 * <p>这是测试输出（同 JUnit 报告），不是领域结论、不是系统准确率、不进知识库。
 * 数字来自程序实测的检索返回，报告只声称「改动前后检索有没有变差」与「重跑是否一致」。
 */
public final class RagEvalReport {

    public record CaseResult(
            String id,
            String category,
            boolean scored,
            boolean mustRefuse,
            String refusalLayer,
            List<String> expected,
            List<String> returned,
            List<String> hit,
            double recall,
            double precision,
            boolean mustRefuseOk,
            String status,
            String error,
            Double topScore) {
        public boolean countedInRecall() {
            return scored && "OK".equals(status) && !mustRefuse && !expected.isEmpty();
        }

        public boolean retrievalLayerRefusal() {
            return mustRefuse && RagGoldenSet.RETRIEVAL_LAYER.equals(refusalLayer)
                    && "OK".equals(status);
        }
    }

    private final Map<String, String> meta;
    private final List<CaseResult> results;

    public RagEvalReport(Map<String, String> meta, List<CaseResult> results) {
        this.meta = new LinkedHashMap<>(meta);
        this.meta.putIfAbsent("generatedAt", Instant.now().toString());
        this.results = List.copyOf(results);
    }

    public List<CaseResult> results() {
        return results;
    }

    public long countedCases() {
        return results.stream().filter(CaseResult::countedInRecall).count();
    }

    public double meanRecall() {
        return mean(results.stream().filter(CaseResult::countedInRecall)
                .mapToDouble(CaseResult::recall));
    }

    public double meanPrecision() {
        return mean(results.stream().filter(CaseResult::countedInRecall)
                .mapToDouble(CaseResult::precision));
    }

    /**
     * 阶段 8：MRR（Mean Reciprocal Rank）。
     *
     * <p>Recall/Precision 只看「有没有召回」，不看「排在第几」。而 RAG 最终只把 top-k
     * 前几条塞进上下文，第 1 名正确与第 5 名正确对答案质量完全不同。MRR 补的就是
     * 这个位置信息：第一个命中的期望文档在返回列表中的位置倒数，无命中记 0。
     *
     * <p>位置按<b>去重后</b>的列表算——同一篇文档的多个 chunk 归一成一条，
     * 与 Precision 的口径一致；否则「4 个 chunk 连着命中」会让 rank 虚高。
     */
    public static double reciprocalRank(CaseResult r) {
        if (r.expected() == null || r.expected().isEmpty()
                || r.returned() == null || r.returned().isEmpty()) {
            return 0.0;
        }
        Set<String> expected = new HashSet<>(r.expected());
        List<String> returned = r.returned();
        for (int i = 0; i < returned.size(); i++) {
            if (expected.contains(returned.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 阶段 8：nDCG@|returned|（相关性只有 0/1 的二元口径）。
     *
     * <p>与 MRR 的分工：MRR 只看第一个命中，nDCG 看整份名单的排序质量
     * （多个期望文档时，谁排在前面更影响 nDCG）。
     *
     * <p>IDCG 的分母取 {@code min(|expected|, |returned|)}：期望 3 篇但只返回 2 条时，
     * 理想情况最多也只能命中 2 篇，用 3 做分母会得到一个永远拿不满的分数。
     */
    public static double ndcg(CaseResult r) {
        if (r.expected() == null || r.expected().isEmpty()
                || r.returned() == null || r.returned().isEmpty()) {
            return 0.0;
        }
        Set<String> expected = new HashSet<>(r.expected());
        List<String> returned = r.returned();

        double dcg = 0.0;
        for (int i = 0; i < returned.size(); i++) {
            if (expected.contains(returned.get(i))) {
                // 位置 i（0 基）的折扣是 log2(i+2)
                dcg += 1.0 / (Math.log(i + 2.0) / Math.log(2.0));
            }
        }
        int idealHits = Math.min(expected.size(), returned.size());
        double idcg = 0.0;
        for (int j = 1; j <= idealHits; j++) {
            idcg += 1.0 / (Math.log(j + 1.0) / Math.log(2.0));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    public double meanMrr() {
        return mean(results.stream().filter(CaseResult::countedInRecall)
                .mapToDouble(RagEvalReport::reciprocalRank));
    }

    public double meanNdcg() {
        return mean(results.stream().filter(CaseResult::countedInRecall)
                .mapToDouble(RagEvalReport::ndcg));
    }

    /** 全口径拒答通过率：分母与 4A 基线完全一致，禁止改定义，否则历史快照失去可比性。 */
    public double refusalPassRate() {
        var refusals = results.stream()
                .filter(r -> r.mustRefuse() && "OK".equals(r.status())).toList();
        if (refusals.isEmpty()) {
            return 0.0;
        }
        return refusals.stream().filter(CaseResult::mustRefuseOk).count() / (double) refusals.size();
    }

    /** 只统计「检索层就该空返回」的题；答案层题不背检索层的锅。 */
    public double retrievalRefusalPassRate() {
        var refusals = results.stream().filter(CaseResult::retrievalLayerRefusal).toList();
        if (refusals.isEmpty()) {
            return 0.0;
        }
        return refusals.stream().filter(CaseResult::mustRefuseOk).count() / (double) refusals.size();
    }

    /** 资料存在但说法不被支持、需要 LLM 作答阶段拒答的题数（不进 CI 硬门禁）。 */
    public long answerLayerRefusalCount() {
        return results.stream().filter(r -> r.mustRefuse()
                && RagGoldenSet.ANSWER_LAYER.equals(r.refusalLayer())).count();
    }

    public long errorCount() {
        return results.stream().filter(r -> !"OK".equals(r.status())).count();
    }

    public long registeredCount() {
        return results.stream().filter(r -> !r.scored()).count();
    }

    private static double mean(java.util.stream.DoubleStream values) {
        return values.average().orElse(0.0);
    }

    /** 供离线 runner 落盘；格式稳定，便于两次运行直接 diff。 */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 检索层评测报告\n\n");
        sb.append("> 用途：改动前后对照 / 回归门禁。**不是领域认证，不是系统准确率，不进知识库。**\n\n");
        sb.append("## 运行元信息\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        meta.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        sb.append("\n## 汇总（仅统计 scored=true 且 status=OK 的非拒答题）\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| 计分题数 | ").append(countedCases()).append(" |\n");
        sb.append("| 平均 Recall@k | ").append(fmt(meanRecall())).append(" |\n");
        sb.append("| 平均 Context Precision | ").append(fmt(meanPrecision())).append(" |\n");
        // 阶段 8：Recall/Precision 不看位置，MRR/nDCG 补这一维度。
        // 只看 Recall 会把「第 5 名命中」和「第 1 名命中」算成一样好。
        sb.append("| 平均 MRR | ").append(fmt(meanMrr())).append(" |\n");
        sb.append("| 平均 nDCG@k | ").append(fmt(meanNdcg())).append(" |\n");
        sb.append("| 该拒答题通过率 | ").append(fmt(refusalPassRate())).append(" |\n");
        sb.append("| 检索层拒答通过率 | ").append(fmt(retrievalRefusalPassRate())).append(" |\n");
        sb.append("| 答案层拒答题数（需 LLM 评审） | ").append(answerLayerRefusalCount()).append(" |\n");
        sb.append("| 只登记不计分题数 | ").append(registeredCount()).append(" |\n");
        sb.append("| 异常题数 | ").append(errorCount()).append(" |\n");
        sb.append("\n## 逐题明细\n\n");
        sb.append("| id | 类别 | 计分 | 拒答层 | top1分 | 期望 | 返回 | 命中 | Recall | Precision | 拒答OK | 状态 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (CaseResult r : results) {
            sb.append("| ").append(r.id())
                    .append(" | ").append(r.category())
                    .append(" | ").append(r.scored() ? "是" : "否")
                    .append(" | ").append(r.mustRefuse() ? r.refusalLayer() : "—")
                    .append(" | ").append(fmtScore(r.topScore()))
                    .append(" | ").append(join(r.expected()))
                    .append(" | ").append(join(r.returned()))
                    .append(" | ").append(join(r.hit()))
                    .append(" | ").append(r.countedInRecall() ? fmt(r.recall()) : "—")
                    .append(" | ").append(r.countedInRecall() ? fmt(r.precision()) : "—")
                    .append(" | ").append(r.mustRefuse() ? (r.mustRefuseOk() ? "是" : "否") : "—")
                    .append(" | ").append(r.status())
                    .append(" |\n");
            if (r.error() != null && !r.error().isBlank()) {
                sb.append("| | | | | | ↳ ").append(r.error().replace("|", "\\|"))
                        .append(" | | | | | | | |\n");
            }
        }
        sb.append("\n## 边界\n\n");
        sb.append("- 只覆盖 `KnowledgeService.retrieve()` 的检索入口；报告正文口径需打开 report-enabled 后由 C 类题抽样人工评审。\n");
        sb.append("- 返回列表按唯一 docId 去重：同一文档多个 chunk 不重复计入 Precision。\n");
        sb.append("- Recall 只管「检索有没有命中」，不管 LLM 最终答案；检索全命中也可能答错。\n");
        sb.append("- 分数只用于自己跟自己比，禁止对外表述为「准确率 X%」。\n");
        sb.append("- `refusalLayer=ANSWER` 的题（如 A04）是「资料存在但说法不被支持」，应由生成层拒答，检索层返回相关片段不算错；它仍计入全口径拒答率以保持与 4A 基线可比，但不作为调检索阈值的依据。\n");
        sb.append("- `top1分` 是返回结果中第 1 个 chunk 的相似度分数（PgVectorStore 把余弦距离换算后的值）；REST / self-check 数据源不提供分数时显示 —。\n");
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private static String fmtScore(Double v) {
        return v == null ? "—" : String.format("%.4f", v);
    }

    private static String join(List<String> xs) {
        return xs == null || xs.isEmpty() ? "—" : String.join(", ", new ArrayList<>(xs));
    }
}
