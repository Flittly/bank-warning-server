package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /v0/bank/ai/search} 的返回形状。
 *
 * <p>回归点：混合检索打开后，「向量路 0 条 + 关键词路命中」返回的是没有 score 的
 * {@link Document}（{@code SparseRetriever} 只构造 metadata，不设分；{@code RrfFusion}
 * 单路分支原样返回）。此前用 {@code Map.of} 组装返回值，null 值直接抛 NPE ⇒ 整个
 * 接口 500。这组测试钉住「null 分数要能正常返回，且能被前端识别」。
 */
class KnowledgeControllerTest {

    private static Document doc(String docId, String text, Double score) {
        Document d = new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text(text).build())
                .docId(docId)
                .chunkId(docId + "_c0")
                .build());
        d.setScore(score);
        return d;
    }

    /** 只 mock 掉检索服务，其余依赖传 null——search() 不碰它们。 */
    private static KnowledgeController controllerReturning(List<Document> hits) {
        KnowledgeService service = mock(KnowledgeService.class);
        when(service.retrieve(anyString(), any(KnowledgeService.Scenario.class),
                any(AiCaller.class), any())).thenReturn(hits);
        return new KnowledgeController(service, null, null, null);
    }

    @Test
    @DisplayName("关键词路命中无分数：返回 200 与 score=null，而不是 NPE → 500")
    void searchToleratesNullScore() {
        KnowledgeController controller = controllerReturning(List.of(doc("BMS.docx", "Zb 20 米", null)));

        List<Map<String, Object>> hits = controller.search("Zb", null, 5);

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).containsKey("score"), "score 必须存在，前端靠它判断有无分数");
        assertNull(hits.get(0).get("score"));
        assertEquals(Boolean.FALSE, hits.get(0).get("scored"));
        assertEquals("BMS.docx", hits.get(0).get("docId"));
        assertEquals("Zb 20 米", hits.get(0).get("content"));
    }

    @Test
    @DisplayName("有分数时原样透传，scored=true")
    void searchKeepsScoreWhenPresent() {
        KnowledgeController controller = controllerReturning(List.of(doc("BMS.docx", "Zb 20 米", 0.738211)));

        List<Map<String, Object>> hits = controller.search("Zb", null, 5);

        assertEquals(0.738211, (Double) hits.get(0).get("score"), 1e-9);
        assertEquals(Boolean.TRUE, hits.get(0).get("scored"));
    }

    @Test
    @DisplayName("0 分与「没有分数」必须能区分：0.0 的 scored 仍是 true")
    void zeroScoreIsNotTreatedAsMissing() {
        KnowledgeController controller = controllerReturning(List.of(doc("BMS.docx", "Zb 20 米", 0.0)));

        List<Map<String, Object>> hits = controller.search("Zb", null, 5);

        assertEquals(0.0, (Double) hits.get(0).get("score"), 1e-9);
        assertEquals(Boolean.TRUE, hits.get(0).get("scored"));
    }

    @Test
    @DisplayName("字段顺序稳定：content / score / scored / docId")
    void hitMapKeepsFieldOrder() {
        Map<String, Object> hit = KnowledgeController.toHitMap(doc("BMS.docx", "text", null));

        assertEquals(List.of("content", "score", "scored", "docId"), List.copyOf(hit.keySet()));
    }
}
