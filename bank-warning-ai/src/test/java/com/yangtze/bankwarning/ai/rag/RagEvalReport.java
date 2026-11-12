package com.yangtze.bankwarning.ai.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            List<String> expected,
            List<String> returned,
            List<String> hit,
            double recall,
            double precision,
            boolean mustRefuseOk,
            String status,
            String error) {
        public boolean countedInRecall() {
            return scored && "OK".equals(status) && !mustRefuse && !expected.isEmpty();
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

    public double refusalPassRate() {
        var refusals = results.stream()
                .filter(r -> r.mustRefuse() && "OK".equals(r.status())).toList();
        if (refusals.isEmpty()) {
            return 0.0;
        }
        return refusals.stream().filter(CaseResult::mustRefuseOk).count() / (double) refusals.size();
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
        sb.append("| 该拒答题通过率 | ").append(fmt(refusalPassRate())).append(" |\n");
        sb.append("| 只登记不计分题数 | ").append(registeredCount()).append(" |\n");
        sb.append("| 异常题数 | ").append(errorCount()).append(" |\n");
        sb.append("\n## 逐题明细\n\n");
        sb.append("| id | 类别 | 计分 | 期望 | 返回 | 命中 | Recall | Precision | 拒答OK | 状态 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (CaseResult r : results) {
            sb.append("| ").append(r.id())
                    .append(" | ").append(r.category())
                    .append(" | ").append(r.scored() ? "是" : "否")
                    .append(" | ").append(join(r.expected()))
                    .append(" | ").append(join(r.returned()))
                    .append(" | ").append(join(r.hit()))
                    .append(" | ").append(r.countedInRecall() ? fmt(r.recall()) : "—")
                    .append(" | ").append(r.countedInRecall() ? fmt(r.precision()) : "—")
                    .append(" | ").append(r.mustRefuse() ? (r.mustRefuseOk() ? "是" : "否") : "—")
                    .append(" | ").append(r.status())
                    .append(" |\n");
            if (r.error() != null && !r.error().isBlank()) {
                sb.append("| | | | ↳ ").append(r.error().replace("|", "\\|")).append(" | | | | | | | |\n");
            }
        }
        sb.append("\n## 边界\n\n");
        sb.append("- 只覆盖 `KnowledgeService.retrieve()` 的检索入口；报告正文口径需打开 report-enabled 后由 C 类题抽样人工评审。\n");
        sb.append("- 返回列表按唯一 docId 去重：同一文档多个 chunk 不重复计入 Precision。\n");
        sb.append("- Recall 只管「检索有没有命中」，不管 LLM 最终答案；检索全命中也可能答错。\n");
        sb.append("- 分数只用于自己跟自己比，禁止对外表述为「准确率 X%」。\n");
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private static String join(List<String> xs) {
        return xs == null || xs.isEmpty() ? "—" : String.join(", ", new ArrayList<>(xs));
    }
}
