package com.yangtze.bankwarning.ai.service;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4：来源标签与编号渲染。
 *
 * <p>断言的落点不是"格式好看"，而是三件会影响正确性的事：
 * 段号必须是 1 基（否则「第 0 段」被当成解析失败）、
 * 缺信息时必须降级而不是编造（分母错比没有分母更糟）、
 * 契约文案必须真的禁止编造来源。
 */
@DisplayName("阶段 4：检索结果的来源标注")
class SourceCitationTest {

    private static Document doc(String docId, String chunkId, String index, String total) {
        DocumentMetadata.Builder b = DocumentMetadata.builder()
                .content(TextBlock.builder().text("正文内容").build())
                .docId(docId)
                .chunkId(chunkId);
        if (index != null) {
            b.addPayload(SourceCitation.PAYLOAD_CHUNK_INDEX, index);
        }
        if (total != null) {
            b.addPayload(SourceCitation.PAYLOAD_TOTAL_CHUNKS, total);
        }
        return new Document(b.build());
    }

    @Test
    @DisplayName("payload 齐全：0 基序号显示成「第 3/4 段」")
    void rendersOneBasedIndexWithTotal() {
        Document d = doc("BMS.docx", "BMS.docx_chunk_2", "2", "4");

        assertEquals(3, SourceCitation.chunkNo(d));
        assertEquals(4, SourceCitation.totalChunks(d));
        assertEquals("BMS.docx · 第 3/4 段", SourceCitation.label(d));
    }

    @Test
    @DisplayName("没有 payload 时从 chunk_id 后缀兜底，仍能给出段号")
    void fallsBackToChunkIdSuffix() {
        Document d = doc("BMS.docx", "BMS.docx_chunk_12", null, null);

        assertEquals(13, SourceCitation.chunkNo(d), "chunk_id 里也是 0 基，显示要 +1");
        assertEquals(-1, SourceCitation.totalChunks(d));
        assertEquals("BMS.docx · 第 13 段", SourceCitation.label(d), "总段数未知时不写分母");
    }

    @Test
    @DisplayName("payload 与 chunk_id 都看不出来源：只显示文件名，不猜段号")
    void degradesToDocNameOnly() {
        Document d = doc("人工录入.txt", "manual-row-7", null, null);

        assertEquals(-1, SourceCitation.chunkNo(d));
        assertEquals("人工录入.txt", SourceCitation.label(d));
    }

    @Test
    @DisplayName("docId 为空：给占位文案，不能把 null 写进提示词")
    void blankDocIdBecomesPlaceholder() {
        Document d = doc("  ", "x_chunk_0", "0", null);

        assertEquals("未命名资料 · 第 1 段", SourceCitation.label(d));
    }

    @Test
    @DisplayName("总段数小于段号：说明数据不一致，宁可不写分母")
    void ignoresInconsistentTotal() {
        Document d = doc("x.docx", "x_chunk_9", "9", "4");

        assertEquals("x.docx · 第 10 段", SourceCitation.label(d),
                "10 > 4 时写「第 10/4 段」会让人以为系统算错了");
    }

    @Test
    @DisplayName("payload 脏值不抛异常，按「未知」降级——检索不能因为一行坏数据整批失败")
    void dirtyPayloadIsTreatedAsUnknown() {
        Document d = doc("x.docx", "x_chunk_5", "第二段", "四");

        assertEquals(6, SourceCitation.chunkNo(d), "脏的 chunkIndex 退回 chunk_id 兜底");
        assertEquals("x.docx · 第 6 段", SourceCitation.label(d));
    }

    @Test
    @DisplayName("render：编号从 1 开始、每条带来源行、块间空行")
    void rendersNumberedBlocks() {
        List<Document> docs = List.of(
                doc("BMS.docx", "BMS.docx_chunk_2", "2", "4"),
                doc("3-项目技术报告.docx", "r_chunk_11", "11", "239"));

        String out = SourceCitation.render(docs);

        assertTrue(out.startsWith("[1] 来源：BMS.docx · 第 3/4 段\n正文内容"), out);
        assertTrue(out.contains("[2] 来源：3-项目技术报告.docx · 第 12/239 段"), out);
        assertTrue(out.contains("\n\n"), "块之间要有空行，否则模型会把两段读成一段");
    }

    @Test
    @DisplayName("空结果渲染成空串，由调用方决定「没查到」怎么说")
    void rendersNothingForEmptyList() {
        assertEquals("", SourceCitation.render(List.of()));
        assertEquals("", SourceCitation.render(null));
    }

    @Test
    @DisplayName("契约必须明确禁止编造来源，并允许未覆盖时明说没有依据")
    void ruleForbidsFabrication() {
        assertTrue(SourceCitation.RULE.contains("不得编造"));
        assertTrue(SourceCitation.RULE.contains("知识库中没有相关依据"));
        assertTrue(SourceCitation.RULE.contains("[n]"), "要给出可模仿的编号写法");
        assertFalse(SourceCitation.RULE.isBlank());
    }
}
