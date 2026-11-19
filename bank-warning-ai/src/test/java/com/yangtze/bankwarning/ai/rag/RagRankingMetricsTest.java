package com.yangtze.bankwarning.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阶段 8：MRR / nDCG 的口径测试。
 *
 * <p>这两个指标最容易出错的地方不是公式，而是<b>分母</b>：
 * MRR 的位置要不要按去重后算、nDCG 的 IDCG 用 expected 数还是「expected 与 returned 的较小值」。
 * 口径定错不会报错，只会让两次改动之间的对比失去意义。
 */
@DisplayName("阶段 8：MRR / nDCG 口径")
class RagRankingMetricsTest {

    private static RagEvalReport.CaseResult caseOf(List<String> expected, List<String> returned) {
        return new RagEvalReport.CaseResult(
                "T01", "A", true, false, RagGoldenSet.RETRIEVAL_LAYER,
                expected, returned,
                // hit 字段不参与 MRR/nDCG 计算，这里给空表即可
                List.of(),
                0.0, 0.0, true, "OK", null, null);
    }

    @Test
    @DisplayName("MRR：第一个命中在第 1 位得 1.0，第 3 位得 1/3")
    void mrrUsesFirstHitPosition() {
        assertEquals(1.0, RagEvalReport.reciprocalRank(
                caseOf(List.of("A"), List.of("A", "B", "C"))), 1e-9);
        assertEquals(1.0 / 3, RagEvalReport.reciprocalRank(
                caseOf(List.of("A"), List.of("X", "Y", "A"))), 1e-9);
        assertEquals(0.0, RagEvalReport.reciprocalRank(
                caseOf(List.of("A"), List.of("X", "Y", "Z"))), 1e-9);
    }

    @Test
    @DisplayName("MRR：期望多篇时只看最先命中的那篇，不看命中总数")
    void mrrOnlyCaresAboutFirstHit() {
        // 第 1 位就命中，哪怕只召回 1 篇里的 1 篇，也是满分
        assertEquals(1.0, RagEvalReport.reciprocalRank(
                caseOf(List.of("A", "B", "C"), List.of("A"))), 1e-9);
        // 命中的是第 2 位
        assertEquals(0.5, RagEvalReport.reciprocalRank(
                caseOf(List.of("A", "B"), List.of("X", "B"))), 1e-9);
    }

    @Test
    @DisplayName("nDCG：全部期望文档占据最前位置时为 1.0")
    void ndcgPerfectRanking() {
        assertEquals(1.0, RagEvalReport.ndcg(
                caseOf(List.of("A", "B"), List.of("A", "B", "X"))), 1e-9);
    }

    @Test
    @DisplayName("nDCG：命中但排在后面，分数介于 0 与 1 之间")
    void ndcgPenalizesLateHits() {
        double late = RagEvalReport.ndcg(caseOf(List.of("A"), List.of("X", "Y", "A")));
        double first = RagEvalReport.ndcg(caseOf(List.of("A"), List.of("A", "X", "Y")));
        assertEquals(1.0, first, 1e-9);
        org.junit.jupiter.api.Assertions.assertTrue(late > 0 && late < 1,
                "第 3 位命中应在 (0,1) 之间，实际=" + late);
    }

    @Test
    @DisplayName("nDCG：IDCG 取 min(expected, returned)，期望 3 篇只返回 2 条也能拿满分")
    void ndcgIdealUsesMinOfExpectedAndReturned() {
        // 期望 3 篇，topK=2 只可能召回 2 篇：两条都命中就是理论上限，应得 1.0
        assertEquals(1.0, RagEvalReport.ndcg(
                caseOf(List.of("A", "B", "C"), List.of("A", "B"))), 1e-9);
    }

    @Test
    @DisplayName("期望为空（拒答题）：两个指标都记 0，不参与均值")
    void emptyExpectedScoresZero() {
        assertEquals(0.0, RagEvalReport.reciprocalRank(caseOf(List.of(), List.of("A"))), 1e-9);
        assertEquals(0.0, RagEvalReport.ndcg(caseOf(List.of(), List.of("A"))), 1e-9);
    }

    @Test
    @DisplayName("返回为空：两个指标都是 0，不抛异常")
    void emptyReturnedScoresZero() {
        assertEquals(0.0, RagEvalReport.reciprocalRank(caseOf(List.of("A"), List.of())), 1e-9);
        assertEquals(0.0, RagEvalReport.ndcg(caseOf(List.of("A"), List.of())), 1e-9);
    }
}
