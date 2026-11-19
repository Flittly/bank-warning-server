package com.yangtze.bankwarning.ai.service;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阶段 8：加权 RRF 的守门测试。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>等权必须与阶段 5 逐位一致</b>——{@code 1/(k+rank) * 1.0} 不得引入任何浮点漂移，
 *       否则「打开权重」这件事本身就改变了线上结果；</li>
 *   <li><b>权重必须真的能改变排序</b>——否则参数形同虚设，评测格点搜索搜出来的是噪声；</li>
 *   <li><b>单路时权重不生效</b>——只有一路乘权重等于给分数换单位，会破坏与关闭态的可比性。</li>
 * </ol>
 */
@DisplayName("阶段 8：加权 RRF 与等权退化")
class RrfWeightedFusionTest {

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
    @DisplayName("等权 1.0/1.0：分数与旧 4 参入口逐位相等")
    void unitWeightsMatchLegacyExactly() {
        Document a = doc("A", "A_c0", 0.90);
        Document b = doc("B", "B_c0", 0.80);
        Document c = doc("C", "C_c0", 0.70);

        List<Document> legacy = RrfFusion.fuse(List.of(a, b), List.of(c, a), 60, 3);
        List<Document> weighted = RrfFusion.fuse(List.of(a, b), List.of(c, a), 60, 3, 1.0, 1.0);

        assertEquals(legacy.size(), weighted.size());
        for (int i = 0; i < legacy.size(); i++) {
            assertEquals(legacy.get(i).getMetadata().getDocId(),
                    weighted.get(i).getMetadata().getDocId(), "第 " + i + " 名顺序变了");
            // 逐位相等，不是「近似相等」：1.0 的乘法在 IEEE754 下必须恒等。
            assertEquals(legacy.get(i).getScore(), weighted.get(i).getScore(), 0.0);
        }
    }

    @Test
    @DisplayName("提高向量路权重：向量路独占的段挤到关键词路独占的段之前")
    void heavierDenseWeightPromotesDenseOnlyChunk() {
        Document a = doc("A", "A_c0", 0.90);   // dense 第 1
        Document b = doc("B", "B_c0", 0.80);   // dense 第 2
        Document c = doc("C", "C_c0", 0.70);   // sparse 第 1

        // 等权时：C 的 1/61 与 B 的 1/62 接近，但 C 略高 → C 在 B 前
        List<Document> equal = RrfFusion.fuse(List.of(a, b), List.of(c), 60, 3);
        assertEquals("C", equal.get(1).getMetadata().getDocId(), "等权基准：C 应略高于 B");

        // 向量路权重放大到 2 倍：B 的 2/62 = 0.03226 > C 的 1/61 = 0.01639 → B 反超
        List<Document> denseHeavy = RrfFusion.fuse(List.of(a, b), List.of(c), 60, 3, 2.0, 1.0);
        assertEquals("A", denseHeavy.get(0).getMetadata().getDocId());
        assertEquals("B", denseHeavy.get(1).getMetadata().getDocId(), "向量路权重加倍后 B 应反超 C");
        assertEquals("C", denseHeavy.get(2).getMetadata().getDocId());
    }

    @Test
    @DisplayName("关键词路权重为 0：退化成纯向量排序，等价于 dense-only 的顺序")
    void zeroSparseWeightDegradesToDenseOrder() {
        Document a = doc("A", "A_c0", 0.90);
        Document b = doc("B", "B_c0", 0.80);
        Document c = doc("C", "C_c0", 0.70);

        List<Document> fused = RrfFusion.fuse(List.of(a, b), List.of(c, b), 60, 3, 1.0, 0.0);

        // b 两路都有但 sparse 权重为 0，只剩 dense 名次分；a 是 dense 第 1 名
        assertEquals("A", fused.get(0).getMetadata().getDocId());
        assertEquals("B", fused.get(1).getMetadata().getDocId(), "b 有 dense 名次分，应排在只有 sparse 分的 c 前面");
        assertEquals("C", fused.get(2).getMetadata().getDocId());
    }

    @Test
    @DisplayName("单路时权重不生效：分数保持原始余弦值")
    void singlePathIgnoresWeights() {
        Document a = doc("A", "A_c0", 0.91);
        Document b = doc("B", "B_c0", 0.55);

        List<Document> fused = RrfFusion.fuse(List.of(a, b), List.of(), 60, 2, 5.0, 5.0);

        assertEquals(0.91, fused.get(0).getScore(), 0.0, "单路返回不得乘权重");
        assertEquals(0.55, fused.get(1).getScore(), 0.0);
    }

    @Test
    @DisplayName("负权重按 1.0 处理，不产生负分或翻转排序")
    void negativeWeightsFallBackToUnit() {
        Document a = doc("A", "A_c0", 0.90);
        Document b = doc("B", "B_c0", 0.80);
        Document c = doc("C", "C_c0", 0.70);

        List<Document> weird = RrfFusion.fuse(List.of(a, b), List.of(c, a), 60, 3, -1.0, -1.0);
        List<Document> normal = RrfFusion.fuse(List.of(a, b), List.of(c, a), 60, 3);

        for (int i = 0; i < normal.size(); i++) {
            assertEquals(normal.get(i).getMetadata().getDocId(),
                    weird.get(i).getMetadata().getDocId());
            assertEquals(normal.get(i).getScore(), weird.get(i).getScore(), 0.0);
        }
    }
}
