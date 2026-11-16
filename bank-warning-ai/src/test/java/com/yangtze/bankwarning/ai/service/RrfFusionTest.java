package com.yangtze.bankwarning.ai.service;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 5：RRF 融合的确定性单测（无 Spring、无数据库、无网络）。
 *
 * <p>重点守两条容易写错的行为：① 两路共有的段分数相加并前移；② <b>单路有结果时原样返回，
 * 不改写分数</b>——否则「开关打开但提不出锚点」与关闭态结果不一致，4A 基线就不再可比。
 */
@DisplayName("阶段 5：RRF 融合只看名次、单路不改写分数")
class RrfFusionTest {

    private static Document doc(String docId, String chunkId, Double score) {
        Document d = new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text(docId + "/" + chunkId).build())
                .docId(docId)
                .chunkId(chunkId)
                .build());
        d.setScore(score);
        return d;
    }

    @Test
    @DisplayName("两路都命中的段：分数相加并排到最前")
    void commonChunkScoresAddUpAndMovesFirst() {
        Document a = doc("A", "A_c0", 0.90);
        Document b = doc("B", "B_c0", 0.80);
        Document c = doc("C", "C_c0", 0.70);

        // dense 名次: A(1) B(2)；sparse 名次: C(1) A(2)
        List<Document> fused = RrfFusion.fuse(List.of(a, b), List.of(c, a), 60, 3);

        assertEquals(3, fused.size());
        assertEquals("A", fused.get(0).getMetadata().getDocId(), "两路都命中的 A 应排第一");
        assertEquals(1.0 / 61 + 1.0 / 62, fused.get(0).getScore(), 1e-9);
        assertEquals("C", fused.get(1).getMetadata().getDocId(), "sparse 第 1 名次分高于 dense 第 2 名次分");
        assertEquals("B", fused.get(2).getMetadata().getDocId());
    }

    @Test
    @DisplayName("关键词路为空：原样返回向量结果，分数仍是余弦相似度")
    void sparseEmptyKeepsDenseScoresUntouched() {
        Document a = doc("A", "A_c0", 0.91);
        Document b = doc("B", "B_c0", 0.55);

        List<Document> fused = RrfFusion.fuse(List.of(a, b), List.of(), 60, 5);

        assertEquals(2, fused.size());
        assertEquals("A", fused.get(0).getMetadata().getDocId());
        assertEquals(0.91, fused.get(0).getScore(), 1e-9,
                "提不出锚点时不得把余弦分改写成 RRF 分");
        assertEquals(0.55, fused.get(1).getScore(), 1e-9);
    }

    @Test
    @DisplayName("向量路为空：改用关键词结果，同样不改写分数")
    void denseEmptyUsesSparseAsIs() {
        Document c = doc("C", "C_c0", null);

        List<Document> fused = RrfFusion.fuse(List.of(), List.of(c), 60, 5);

        assertEquals(1, fused.size());
        assertEquals("C", fused.get(0).getMetadata().getDocId());
        assertNull(fused.get(0).getScore(), "原始分数为 null 时不应被凭空造出 RRF 分");
    }

    @Test
    @DisplayName("同一 docId 的不同 chunk 不合并：去重键含 chunkId")
    void chunksOfSameDocumentStayDistinct() {
        Document a0 = doc("A", "A_c0", 0.90);
        Document a1 = doc("A", "A_c1", 0.80);

        List<Document> fused = RrfFusion.fuse(List.of(a0, a1), List.of(a1), 60, 5);

        assertEquals(2, fused.size(), "两段必须各占一条，不能被 docId 合并成一条");
        assertEquals("A_c1", fused.get(0).getMetadata().getChunkId());
    }

    @Test
    @DisplayName("limit 在融合之后截断，且不改变排名")
    void limitTruncatesAfterFusion() {
        Document a = doc("A", "A_c0", 0.9);
        Document b = doc("B", "B_c0", 0.8);
        Document c = doc("C", "C_c0", 0.7);

        List<Document> fused = RrfFusion.fuse(List.of(a, b, c), List.of(a), 60, 2);

        assertEquals(2, fused.size());
        assertEquals("A", fused.get(0).getMetadata().getDocId());
        assertTrue(fused.stream().noneMatch(d -> "C".equals(d.getMetadata().getDocId())));
    }

    @Test
    @DisplayName("k 越大，同分段的差距被压得越平")
    void largerKSmoothsRankDifference() {
        // dense: A(名次 1) B(名次 2)；sparse: B(名次 1)
        // A = 1/(k+1)，B = 1/(k+2) + 1/(k+1)
        Document a = doc("A", "A_c0", 0.9);
        Document b = doc("B", "B_c0", 0.8);

        List<Document> smallK = RrfFusion.fuse(List.of(a, b), List.of(b), 1, 5);
        List<Document> largeK = RrfFusion.fuse(List.of(a, b), List.of(b), 1000, 5);

        double smallGap = Math.abs(smallK.get(0).getScore() - smallK.get(1).getScore());
        double largeGap = Math.abs(largeK.get(0).getScore() - largeK.get(1).getScore());
        assertTrue(smallGap > largeGap,
                "k=1 时名次差距明显，k=1000 时被压平：" + smallGap + " vs " + largeGap);
    }
}
