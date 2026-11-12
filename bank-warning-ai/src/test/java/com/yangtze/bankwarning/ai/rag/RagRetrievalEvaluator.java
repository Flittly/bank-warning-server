package com.yangtze.bankwarning.ai.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 阶段 4 检索层评测器：跑考卷、算 Recall@k / Context Precision / 该拒答。
 *
 * <p>纯 POJO，不依赖 Spring 容器、数据库或真实 LLM。检索能力由调用方以
 * {@link RetrievalFn} 注入，但**实现必须走 `KnowledgeService.retrieve()` 这个唯一入口**
 * （或等价的线上 REST 入口），否则测的是另一套实现，绿了也不能说明线上没问题。
 *
 * <p>检索失败记 status=ERROR 而不是当成「没查到」——这正是阶段 1 消灭静默失败后
 * 必须守住的契约：空列表只允许表示「确实没命中」。
 */
public final class RagRetrievalEvaluator {

    /** 返回归一化后的 docId 列表；抛异常表示检索本身失败。 */
    @FunctionalInterface
    public interface RetrievalFn {
        List<String> fetch(String question, int topK, String caller, String traceId) throws Exception;
    }

    public static final int DEFAULT_TOP_K = 5;

    private final int topK;

    public RagRetrievalEvaluator() {
        this(DEFAULT_TOP_K);
    }

    public RagRetrievalEvaluator(int topK) {
        this.topK = topK;
    }

    public RagEvalReport run(RagGoldenSet golden, RetrievalFn retrieval) {
        golden.validate();
        String runId = "eval-" + UUID.randomUUID();
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("runId", runId);
        meta.put("goldenVersion", golden.version);
        meta.put("topK", String.valueOf(topK));
        meta.put("caseCount", String.valueOf(golden.cases.size()));
        meta.put("scoredCount", String.valueOf(golden.scoredCount()));

        List<RagEvalReport.CaseResult> results = new ArrayList<>();
        for (RagGoldenSet.GoldenCase c : golden.cases) {
            List<String> expected = List.copyOf(c.expectedDocIdsOrEmpty());
            String caller = (c.caller == null || c.caller.isBlank()) ? "admin" : c.caller;
            String traceId = runId + "-" + c.id;
            try {
                List<String> returned = retrieval.fetch(c.question, topK, caller, traceId);
                // 按唯一 docId 去重：同一篇文档的 4 个 chunk 归一化后是同一个 id，
                // 不去重会让 Precision 被重复计数压低（实测 1.0 被算成 0.25）。
                List<String> safeReturned = returned == null
                        ? List.of()
                        : List.copyOf(new LinkedHashSet<>(returned));
                Set<String> expectedSet = new LinkedHashSet<>(expected);
                Set<String> returnedSet = new LinkedHashSet<>(safeReturned);
                List<String> hit = new ArrayList<>(expectedSet);
                hit.retainAll(returnedSet);

                double recall = expected.isEmpty() ? 0.0 : hit.size() / (double) expected.size();
                double precision = safeReturned.isEmpty() ? 0.0 : hit.size() / (double) safeReturned.size();
                boolean refuseOk = !c.mustRefuse || safeReturned.isEmpty();
                results.add(new RagEvalReport.CaseResult(
                        c.id, c.category, c.scored, c.mustRefuse,
                        expected, safeReturned, hit, recall, precision,
                        refuseOk, "OK", null));
            } catch (Exception e) {
                // 不吞成空列表：失败必须留在报告里，且计入 errorCount。
                String msg = rootMessage(e);
                results.add(new RagEvalReport.CaseResult(
                        c.id, c.category, c.scored, c.mustRefuse,
                        expected, List.of(), List.of(), 0.0, 0.0,
                        false, "ERROR", msg));
            }
        }
        return new RagEvalReport(meta, results);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null ? e.toString() : msg).replace('\n', ' ').replace('\r', ' ');
    }
}
