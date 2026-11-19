package com.yangtze.bankwarning.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 5：改写层的两个纯函数——归一化必须无损，扩展必须有边界。
 *
 * <p>这两条看似吹毛求疵，但改写的风险全在「悄悄改变了原意」上：
 * 归一化多删一个字、扩展多匹配一个子串，都会变成召回噪声，而噪声在指标上
 * 只表现为「Precision 掉了一点」，很难被追到具体规则。
 */
@DisplayName("QueryNormalizer / QueryReformulator")
class QueryRewriteTest {

    @Test
    @DisplayName("全角转半角：数值与符号折成同一形态")
    void normalizesFullWidth() {
        assertEquals("Zb=20,阈值0.4", QueryNormalizer.normalize("Zb＝２０，阈值０.４"));
    }

    @Test
    @DisplayName("罗马数字转阿拉伯：报告里的「Ⅲ 级」与代码里的 3 变成同一个数")
    void normalizesRomanNumerals() {
        assertEquals("风险等级3级", QueryNormalizer.normalize("风险等级Ⅲ级"));
        assertEquals("第2档", QueryNormalizer.normalize("第Ⅱ档"));
    }

    @Test
    @DisplayName("零宽字符被移除：从网页/Word 复制来的不可见字符会拆词")
    void stripsZeroWidth() {
        assertEquals("崩岸预警", QueryNormalizer.normalize("崩\u200B岸\uFEFF预警"));
    }

    @Test
    @DisplayName("中文名 → 标识符：用户问中文，关键词路也能命中代码里的 Zb")
    void expandsChineseToIdentifier() {
        List<String> out = QueryReformulator.expansions("滩槽高差的阈值是多少");
        assertTrue(out.contains("Zb"), "应补出 Zb：" + out);
    }

    @Test
    @DisplayName("标识符 → 中文名：反过来同样成立")
    void expandsIdentifierToChinese() {
        List<String> out = QueryReformulator.expansions("Zb 的三级阈值");
        assertTrue(out.contains("滩槽高差"), "应补出中文名：" + out);
    }

    @Test
    @DisplayName("ASCII 键按独立词匹配：safety 里的 sa 不能被当成 Sa")
    void asciiKeyNeedsWordBoundary() {
        assertTrue(QueryReformulator.expansions("safety factor analysis").isEmpty());
        assertEquals(List.of("岸坡坡比"), QueryReformulator.expansions("Sa 怎么算"));
    }

    @Test
    @DisplayName("空问题不产生任何扩展，也不抛异常")
    void blankQueryIsSafe() {
        assertTrue(QueryReformulator.expansions(null).isEmpty());
        assertTrue(QueryReformulator.expansions("  ").isEmpty());
        assertEquals("", QueryNormalizer.normalize(null));
    }

    @Test
    @DisplayName("标识符判定：纯 ASCII 走锚点，含中文走词项")
    void identifierDetection() {
        assertTrue(QueryReformulator.isIdentifier("Zb"));
        assertTrue(QueryReformulator.isIdentifier("risk_level"));
        assertFalse(QueryReformulator.isIdentifier("滩槽高差"));
        assertFalse(QueryReformulator.isIdentifier("1abc"));
    }
}
