package com.yangtze.bankwarning.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构感知切块的守门测试。
 *
 * <p>五条不变量：
 * <ol>
 *   <li><b>纯文本零回归</b>：没有格式头标记时，切块结果与 {@link MdLiteChunker#chunkPlain} 逐字一致；</li>
 *   <li><b>头标记是唯一开关</b>：{@code #} 行本身不触发结构解析——代码语料的注释就是这个形状；</li>
 *   <li><b>标题分节</b>：一块不跨节，{@code headingPath} 说的是它真正所属的那一节；</li>
 *   <li><b>表格原子</b>：不按句号切；超长按行分组，每组都带题注与表头；</li>
 *   <li><b>标记剥离</b>：头标记、标题行、页标记都不进正文。</li>
 * </ol>
 */
@DisplayName("md-lite 切块：分节、表格原子、标记剥离、纯文本零回归")
class MdLiteChunkerTest {

    private static final int MAX = 500;
    private static final int OVERLAP = 50;

    /** 拼一份带格式声明的 md-lite 文档。 */
    private static String md(String... blocks) {
        return MdLiteChunker.FORMAT_MARKER + "\n\n" + String.join("\n\n", blocks);
    }

    @Test
    @DisplayName("纯文本输入：与 chunkPlain 逐字一致，不带任何结构元信息")
    void plainTextDelegatesToLegacyChunker() {
        String text = "甲".repeat(300) + "\n\n" + "乙".repeat(300);
        List<MdLiteChunker.Chunk> chunks = MdLiteChunker.chunk(text, MAX, OVERLAP);
        List<String> legacy = MdLiteChunker.chunkPlain(text, MAX, OVERLAP);

        assertEquals(legacy.size(), chunks.size());
        for (int i = 0; i < legacy.size(); i++) {
            assertEquals(legacy.get(i), chunks.get(i).text(), "第 " + i + " 块正文变了");
            assertEquals("", chunks.get(i).headingPath());
            assertEquals(MdLiteChunker.TYPE_TEXT, chunks.get(i).blockType());
        }
    }

    @Test
    @DisplayName("代码语料守门：只有 # 注释不算结构化，注释不能被当标题剥掉")
    void hashLinesAloneDoNotTriggerStructure() {
        String python = "# 计算风险等级\n"
                + "def calc(x):\n"
                + "    return x * 0.5";

        assertFalse(MdLiteChunker.hasStructure(python),
                "# 行在 Python 语料里是注释，不能当标题");
        List<MdLiteChunker.Chunk> chunks = MdLiteChunker.chunk(python, MAX, OVERLAP);
        assertTrue(chunks.get(0).text().contains("# 计算风险等级"), "注释必须留在正文里");
        assertEquals("", chunks.get(0).headingPath(), "注释不能被写成章节路径");
    }

    @Test
    @DisplayName("头标记 / 表格 / 页标记都算结构化；正文里偶然一个竖线不算")
    void structureDetection() {
        assertFalse(MdLiteChunker.hasStructure("这是一个普通段落，里面有一个 | 竖线。"));
        assertTrue(MdLiteChunker.hasStructure(md("# 一、平台建设概况")));
        assertTrue(MdLiteChunker.hasStructure("| 层次 | 技术栈 |"), "无头标记但出现表格行，按结构解析");
        assertTrue(MdLiteChunker.hasStructure("<!-- page: 2 -->"));
    }

    @Test
    @DisplayName("头标记不进正文")
    void formatMarkerIsStripped() {
        List<MdLiteChunker.Chunk> chunks = MdLiteChunker.chunk(md("# 一、概述", "正文。"), MAX, OVERLAP);

        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).text().contains("md-lite"), "格式声明不该出现在正文里");
        assertEquals("一、概述", chunks.get(0).headingPath());
    }

    @Test
    @DisplayName("标题为界：两块分属两节，headingPath 各自正确")
    void headingSplitsSections() {
        String doc = md(
                "# 二、平台总体架构",
                "平台采用分层架构，自上而下依次为用户与访问层、前端交互层、业务服务层。",
                "# 三、前端交互层",
                "前端以地图交互为主，采用 React 与 Mapbox GL。");

        List<MdLiteChunker.Chunk> chunks = MdLiteChunker.chunk(doc, MAX, OVERLAP);

        assertEquals(2, chunks.size(), "两节应切成两块");
        assertEquals("二、平台总体架构", chunks.get(0).headingPath());
        assertEquals("三、前端交互层", chunks.get(1).headingPath());
        assertFalse(chunks.get(0).text().contains("# 二、平台总体架构"), "标题行不进正文");
    }

    @Test
    @DisplayName("多级标题：子标题覆盖同级、路径逐级拼接")
    void nestedHeadingBuildsPath() {
        String doc = md(
                "# 二、平台总体架构",
                "## （一）设计思路",
                "总体遵循分层解耦。",
                "## （二）总体架构",
                "（二）的正文。");

        List<MdLiteChunker.Chunk> chunks = MdLiteChunker.chunk(doc, MAX, OVERLAP);

        assertEquals(2, chunks.size());
        assertEquals("二、平台总体架构 / （一）设计思路", chunks.get(0).headingPath());
        assertEquals("二、平台总体架构 / （二）总体架构", chunks.get(1).headingPath(),
                "同级标题应覆盖上一个同级标题，不能串成「（一） / （二）」");
    }

    @Test
    @DisplayName("表格成块：题注保留在正文、表头与行都在、类型为 table")
    void tableBecomesOneAtomicChunk() {
        String doc = md(
                "## （二）总体架构",
                "> 表 4-1 平台分层技术选型一览\n"
                        + "| 层次 | 主要技术栈 |\n"
                        + "|---|---|\n"
                        + "| 用户与访问层 | 浏览器 / 大屏终端 |\n"
                        + "| 业务服务层 | Java 21、Spring Boot 4.0 |");

        List<MdLiteChunker.Chunk> chunks = MdLiteChunker.chunk(doc, MAX, OVERLAP);

        assertEquals(1, chunks.size());
        MdLiteChunker.Chunk table = chunks.get(0);
        assertEquals(MdLiteChunker.TYPE_TABLE, table.blockType());
        assertTrue(table.text().startsWith("表 4-1 平台分层技术选型一览"), "题注应保留在正文首行");
        assertFalse(table.text().startsWith(">"), "题注的引用前缀应被剥掉");
        assertTrue(table.text().contains("| 层次 | 主要技术栈 |"), "表头必须随块");
        assertTrue(table.text().contains("用户与访问层"), "表行必须随块");
        assertEquals("（二）总体架构", table.headingPath());
    }

    @Test
    @DisplayName("超长表格：按行分组，每组重述题注与表头")
    void oversizedTableSplitsByRows() {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            row.append("| 行").append(i).append(" | ").append("内容".repeat(40)).append(" |\n");
        }
        String doc = md("> 表 9-1 长表示例\n| 序号 | 说明 |\n|---|---|\n" + row.toString().strip());

        List<MdLiteChunker.Chunk> chunks = MdLiteChunker.chunk(doc, MAX, OVERLAP);

        assertTrue(chunks.size() > 1, "8 行 × 80 字应被分组，实际块数=" + chunks.size());
        for (MdLiteChunker.Chunk c : chunks) {
            assertEquals(MdLiteChunker.TYPE_TABLE, c.blockType());
            assertTrue(c.text().startsWith("表 9-1 长表示例"), "每组都要重述题注");
            assertTrue(c.text().contains("| 序号 | 说明 |"), "每组都要重述表头");
        }
        assertTrue(chunks.get(1).text().contains("（续 2/" + chunks.size() + "）"),
                "续块要标出处：" + chunks.get(1).text().substring(0, Math.min(40, chunks.get(1).text().length())));
        // 所有表行都要落在某一块里，不能因分组丢行
        for (int i = 0; i < 8; i++) {
            final int n = i;
            assertTrue(chunks.stream().anyMatch(c -> c.text().contains("| 行" + n + " |")),
                    "第 " + i + " 行丢了");
        }
    }

    @Test
    @DisplayName("页标记：不进正文，页码进元信息，并在换页处断开")
    void pageMarkerIsStrippedAndRecorded() {
        String doc = md(
                "## 一、概述",
                "第一页的正文。",
                "<!-- page: 2 -->",
                "第二页的正文。");

        List<MdLiteChunker.Chunk> chunks = MdLiteChunker.chunk(doc, MAX, OVERLAP);

        assertEquals(2, chunks.size(), "换页应断开，避免一块跨两页只记一个页码");
        assertEquals(0, chunks.get(0).page(), "首块在页标记之前，页码未知记 0");
        assertEquals(2, chunks.get(1).page());
        for (MdLiteChunker.Chunk c : chunks) {
            assertFalse(c.text().contains("<!--"), "页标记不能进正文");
        }
    }

    @Test
    @DisplayName("空文本：返回空列表，不抛异常")
    void blankReturnsEmpty() {
        assertTrue(MdLiteChunker.chunk("", MAX, OVERLAP).isEmpty());
        assertTrue(MdLiteChunker.chunk("   \n\n  ", MAX, OVERLAP).isEmpty());
        assertTrue(MdLiteChunker.chunk(null, MAX, OVERLAP).isEmpty());
    }
}
