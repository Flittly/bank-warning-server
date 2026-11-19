package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import io.agentscope.core.rag.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 阶段二守卫：入库「要么整篇换掉，要么一动不动」。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li>整批嵌入成功 ⇒ 换名（删旧 + 迁入），正式 doc_id 只剩一份；</li>
 *   <li>任何一段失败 ⇒ 整批作废，旧版本仍可检索（不能用残缺版本覆盖完整版本）；</li>
 *   <li>临时 doc_id 与正式 doc_id 不同形，且长度受限（列是 VARCHAR(256)）。</li>
 * </ol>
 */
@DisplayName("知识入库：临时批次换名 / 失败整批作废")
class KnowledgeIngestStagingTest {

    private KnowledgeService service;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeService.class);
        controller = new KnowledgeController(service, null, null, null,
                EmbeddingBatchProperties.disabled());
    }

    @Test
    @DisplayName("全部段成功：换名成正式 doc_id，且不清理临时批次")
    void allChunksOkPromotesStaging() {
        boolean ok = controller.finishIngest("报告.docx#stg-a1",
                "报告.docx", 7L, new KnowledgeService.IngestResult(3, 3, 0));

        assertTrue(ok);
        verify(service).replaceStaged("报告.docx#stg-a1", "报告.docx", 7L);
        verify(service, never()).discardStaged(anyString());
    }

    @Test
    @DisplayName("有一段失败：整批作废，绝不换名（旧版本保持不变）")
    void partialFailureDiscardsStaging() {
        boolean ok = controller.finishIngest("报告.docx#stg-a2",
                "报告.docx", 7L, new KnowledgeService.IngestResult(3, 2, 1));

        assertFalse(ok);
        verify(service).discardStaged("报告.docx#stg-a2");
        verify(service, never()).replaceStaged(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("零段批次（空内容）：同样不换名，避免把正式文档删空")
    void emptyBatchIsNeverPromoted() {
        boolean ok = controller.finishIngest("报告.docx#stg-a3",
                "报告.docx", 7L, new KnowledgeService.IngestResult(0, 0, 0));

        assertFalse(ok);
        verify(service).discardStaged("报告.docx#stg-a3");
        verify(service, never()).replaceStaged(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("临时 doc_id 每次不同，且不超过列宽 256")
    void stagingDocIdIsUniqueAndBounded() {
        String first = KnowledgeController.stagingDocId("报告.docx");
        String second = KnowledgeController.stagingDocId("报告.docx");

        assertTrue(first.startsWith("报告.docx#stg-"), "临时 id 必须能一眼认出是 staging: " + first);
        assertNotEquals(first, second, "两次入库不能用同一个临时 id，否则并发上传会互相覆盖");

        String longTitle = "长".repeat(300);
        assertTrue(KnowledgeController.stagingDocId(longTitle).length() <= 256,
                "doc_id 列是 VARCHAR(256)，超长会让整批入库直接失败");
    }

    @Test
    @DisplayName("造块口径：docId 用临时 id，chunkId 用正式标题，payload 带页码信息")
    void chunkDocumentsKeepStableChunkIds() {
        List<Document> docs = KnowledgeController.chunkDocuments("报告.docx", "报告.docx#stg-a4",
                List.of("第一块正文", "第二块正文"),
                Map.of("fileName", "报告.docx", "type", "文档"));

        assertEquals(2, docs.size());
        assertEquals("报告.docx#stg-a4", docs.get(0).getMetadata().getDocId());
        assertEquals("报告.docx_chunk_0", docs.get(0).getMetadata().getChunkId(),
                "chunkId 必须用正式标题：换名后它要和检索去重键对得上");
        assertEquals("报告.docx_chunk_1", docs.get(1).getMetadata().getChunkId());
        assertEquals("1", docs.get(1).getMetadata().getPayloadValue("chunkIndex"));
        assertEquals("2", docs.get(1).getMetadata().getPayloadValue("totalChunks"));
        assertEquals("报告.docx", docs.get(1).getMetadata().getPayloadValue("fileName"));
    }

    @Test
    @DisplayName("失败返回体必须带可读原因，不能只给 success=false")
    void failedResponseCarriesReadableError() {
        Map<String, Object> body = KnowledgeController.ingestResponse(false, "报告.docx", 3,
                new KnowledgeService.IngestResult(3, 2, 1), Map.of("isPublic", true));

        assertEquals(Boolean.FALSE, body.get("success"));
        assertEquals(Boolean.TRUE, body.get("isPublic"), "附加字段不能在失败时丢掉");
        assertNotNull(body.get("error"), "没有 error 时前端只能显示「上传失败: 200」");
        assertTrue(String.valueOf(body.get("error")).contains("2/3"), "原因里要带成功段数");
    }

    @Test
    @DisplayName("成功返回体不带 error 字段")
    void successResponseHasNoError() {
        Map<String, Object> body = KnowledgeController.ingestResponse(true, "报告.docx", 3,
                new KnowledgeService.IngestResult(3, 3, 0), Map.of("isPublic", false));

        assertEquals(Boolean.TRUE, body.get("success"));
        assertFalse(body.containsKey("error"));
    }
}
