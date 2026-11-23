package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.KnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 9：切分策略的守门测试。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>{@code overlap=0} 时与旧实现逐字一致</b>——关闭重叠必须是一次零回归操作；</li>
 *   <li><b>任何一段都不得超过 MAX_CHUNK_SIZE</b>——原实现在单段超长时会把整段塞进去，
 *       这是真缺陷，不是取舍；</li>
 *   <li><b>切分不丢字</b>——除了重叠带来的重复，正文内容不能被吞掉。</li>
 * </ol>
 */
@DisplayName("阶段 9：切分不超长、不丢字、关闭重叠时零回归")
class ChunkTextTest {

    private static KnowledgeController controller(int overlap) {
        KnowledgeController c = new KnowledgeController(
                (KnowledgeService) null, null, null, null, null);
        ReflectionTestUtils.setField(c, "chunkOverlap", overlap);
        return c;
    }

    private static final int MAX = 500;

    @Test
    @DisplayName("overlap=0：按空行累积到 500 字，与旧实现一致")
    void defaultKeepsLegacyBehavior() {
        String p1 = "甲".repeat(300);
        String p2 = "乙".repeat(300);
        List<String> chunks = controller(0).chunkText(p1 + "\n\n" + p2);

        assertEquals(2, chunks.size(), "300+300 超过 500，应切成两块");
        assertEquals(p1, chunks.get(0));
        assertEquals(p2, chunks.get(1), "overlap=0 时第二块不应带上一块的尾巴");
    }

    @Test
    @DisplayName("超长单段：必须被切碎，任何一块都不超过 500 字")
    void oversizedParagraphIsSplit() {
        // 旧实现在这里会返回 1 个 1800 字的 chunk
        String longPara = ("崩岸风险等级判定以岸坡坡度为主要指标。").repeat(90);
        List<String> chunks = controller(0).chunkText(longPara);

        assertTrue(chunks.size() > 1, "超长段必须被切成多块，实际块数=" + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= MAX, "存在超过 500 字的 chunk，长度=" + c.length());
        }
    }

    @Test
    @DisplayName("超长段按句末切：块尾应落在句号之后，不从词中间截断")
    void oversizedParagraphCutsAtSentenceEnd() {
        String longPara = ("断面布设间距原则上不超过二百米，形态突变处应加密。").repeat(60);
        List<String> chunks = controller(0).chunkText(longPara);

        for (int i = 0; i < chunks.size() - 1; i++) {
            String c = chunks.get(i);
            assertTrue(c.endsWith("。"),
                    "第 " + i + " 块没有落在句末，尾部=" + c.substring(Math.max(0, c.length() - 10)));
        }
    }

    @Test
    @DisplayName("开 overlap 后：下一块以上一块的尾巴开头")
    void overlapAddsTailToNextChunk() {
        String p1 = "甲".repeat(420) + "。";
        String p2 = "乙".repeat(300);
        List<String> chunks = controller(80).chunkText(p1 + "\n\n" + p2);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(1).startsWith("甲"),
                "第 2 块应以第 1 块的尾部（甲…）开头，实际开头="
                        + chunks.get(1).substring(0, Math.min(10, chunks.get(1).length())));
        assertTrue(chunks.get(1).contains("乙"), "第 2 块正文不能丢");
    }

    @Test
    @DisplayName("切分不丢字：拼接后正文覆盖原文所有段落")
    void doesNotDropContent() {
        String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        List<String> chunks = controller(0).chunkText(text);

        assertEquals(1, chunks.size(), "三段合计远小于 500，应合成一块");
        assertTrue(chunks.get(0).contains("第一段内容"));
        assertTrue(chunks.get(0).contains("第三段内容"));
    }

    @Test
    @DisplayName("空文本与纯空白：不产生 chunk，也不抛异常")
    void blankTextYieldsNothing() {
        assertEquals(List.of(), controller(0).chunkText(""));
        assertEquals(List.of(), controller(0).chunkText("   \n\n  "));
    }

    @Test
    @DisplayName("overlap 超过半块时被夹到 250，不会死循环")
    void overlapIsClampedToHalfChunk() {
        String longPara = ("监测数据入库前须完成坐标系转换。").repeat(120);
        List<String> chunks = controller(999).chunkText(longPara);

        assertTrue(chunks.size() > 1);
        for (String c : chunks) {
            assertTrue(c.length() <= MAX + 250, "夹取后单块长度仍异常=" + c.length());
        }
    }

    // ---------- 重叠量下限 ----------

    private static String tail(String body, int overlap) {
        // 切分实现已搬迁到 MdLiteChunker；这里钉的仍是同一条不变量，
        // 换个类而已——控制器只是薄委托，不该再持有算法副本。
        return ReflectionTestUtils.<String>invokeMethod(
                com.yangtze.bankwarning.ai.service.MdLiteChunker.class, "overlapTail", body, overlap);
    }

    @Test
    @DisplayName("未显式配置时默认带 50 字重叠（块长的 10%）")
    void defaultOverlapTakesEffect() {
        String p1 = "甲".repeat(420);
        String p2 = "乙".repeat(300);
        List<String> chunks = new KnowledgeController(
                (KnowledgeService) null, null, null, null, null)
                .chunkText(p1 + "\n\n" + p2);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(1).startsWith("甲".repeat(50) + "\n\n"),
                "第 2 块应以默认 50 字尾巴开头");
        assertEquals(50 + 2 + 300, chunks.get(1).length(), "重叠量必须等于默认值");
    }

    @Test
    @DisplayName("块本身不比重叠窗口长时不复制整块")
    void overlapTailSkipsWhenBlockNotLongerThanWindow() {
        assertEquals("", tail("甲".repeat(40), 50), "整块复制是重复，不是重叠");
        assertEquals("", tail("甲".repeat(50), 50), "长度相等同样不复制");
    }

    @Test
    @DisplayName("尾巴加新段会超上限时舍弃尾巴，保证块长不破")
    void chunkTextDropsTailWhenItWouldOverflow() {
        String p1 = "甲".repeat(60);
        String p2 = "乙".repeat(480);
        List<String> chunks = controller(50).chunkText(p1 + "\n\n" + p2);

        assertEquals(2, chunks.size());
        assertEquals(p2, chunks.get(1), "50 字尾巴 + 480 字正文超上限，应舍弃尾巴");
        for (String c : chunks) {
            assertTrue(c.length() <= MAX + 2, "块长突破上限：" + c.length());
        }
    }

    @Test
    @DisplayName("块尾是短标题时，仍保住至少一半重叠量")
    void overlapTailKeepsHalfWhenBoundaryIsLate() {
        // 复刻真实退化场景：420 字无标点正文 + 空行 + 14 字标题
        String body = "甲".repeat(420) + "\n\n2.1.3.2 主要地质问题";
        int overlap = 100;
        String t = tail(body, overlap);

        assertTrue(t.length() >= overlap / 2,
                "实际重叠 " + t.length() + " 字，低于下限 " + (overlap / 2) + "；尾巴=「" + t + "」");
        assertEquals(overlap, t.length(), "句界太靠后时应退回完整窗口");
    }

    @Test
    @DisplayName("句界靠近窗口起点时仍从句子边界开始")
    void overlapTailAlignsWhenBoundaryIsEarly() {
        // 窗口起点在「甲…」中段，其后紧接着一个句号
        String body = "甲".repeat(90) + "。" + "乙".repeat(50);
        String t = tail(body, 60);

        assertTrue(t.startsWith("乙"), "应从句号之后开始，实际尾巴=「" + t + "」");
        assertTrue(t.length() >= 30, "对齐后仍须保住至少一半重叠量，实际=" + t.length());
    }

    @Test
    @DisplayName("窗口内没有句末标点时返回完整重叠长度")
    void overlapTailReturnsFullWindowWithoutSentenceEnd() {
        String body = "甲".repeat(500);
        String t = tail(body, 100);

        assertEquals(100, t.length(), "无标点时应原样取满窗口");
    }

    @Test
    @DisplayName("相邻块共享的边界文本不低于配置值的一半")
    void neighbourChunksShareMinimumText() {
        String longPara = "甲".repeat(420);
        String heading = "2.1.3.2 主要地质问题";
        String next = "乙".repeat(300);
        int overlap = 100;
        List<String> chunks = controller(overlap).chunkText(
                longPara + "\n\n" + heading + "\n\n" + next);

        assertEquals(2, chunks.size());
        String second = chunks.get(1);
        assertTrue(second.length() >= overlap / 2 + next.length(),
                "第 2 块应继承至少 " + (overlap / 2) + " 字边界文本，实际长度=" + second.length());
        assertTrue(second.contains(heading), "标题不能丢");
        assertTrue(second.contains(next), "正文不能丢");
    }
}
