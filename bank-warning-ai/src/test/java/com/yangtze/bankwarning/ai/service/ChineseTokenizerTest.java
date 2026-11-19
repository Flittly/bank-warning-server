package com.yangtze.bankwarning.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 3：中文分词与词项整理。
 *
 * <p>断言分两层：<b>词项准入</b>（{@link ChineseTokenizer#keep}）是纯规则、必须精确；
 * <b>分词结果</b>只断言「关键实词在、虚词不在」，不锁死 jieba 的切分细节——
 * 词典会随版本变化，锁太死会让升级依赖变成改测试。
 */
@DisplayName("阶段 3：中文分词（jieba）与词项准入")
class ChineseTokenizerTest {

    // ---------------- 词项准入 ----------------

    @Test
    @DisplayName("单字汉字丢弃：它们几乎出现在每一段里，留着只会扩大噪声")
    void dropsSingleHanChar() {
        assertFalse(ChineseTokenizer.keep("岸"));
        assertFalse(ChineseTokenizer.keep("水"));
        assertTrue(ChineseTokenizer.keep("崩岸"));
    }

    @Test
    @DisplayName("停用词丢弃，且大小写不敏感")
    void dropsStopWords() {
        assertFalse(ChineseTokenizer.keep("的"));
        assertFalse(ChineseTokenizer.keep("什么"));
        assertFalse(ChineseTokenizer.keep("The"));
        assertTrue(ChineseTokenizer.keep("阈值"));
    }

    @Test
    @DisplayName("ASCII 与数字沿用锚点口径：≥2 字符，单字母/单数字丢弃")
    void asciiAndNumbersFollowAnchorThreshold() {
        assertTrue(ChineseTokenizer.keep("Zb"));
        assertTrue(ChineseTokenizer.keep("valueerror"));
        assertTrue(ChineseTokenizer.keep("13.5"));
        assertFalse(ChineseTokenizer.keep("R"));
        assertFalse(ChineseTokenizer.keep("3"));
    }

    @Test
    @DisplayName("标点与符号整词丢弃，不会拼出非法的 tsquery")
    void dropsPunctuation() {
        assertFalse(ChineseTokenizer.keep("，"));
        assertFalse(ChineseTokenizer.keep("("));
        assertFalse(ChineseTokenizer.keep("、"));
        assertFalse(ChineseTokenizer.keep(""));
    }

    // ---------------- 查询侧 ----------------

    @Test
    @DisplayName("纯中文提问能切出实词，虚词被过滤")
    void tokenizesChineseQuestion() {
        List<String> terms = ChineseTokenizer.terms("崩岸预警怎么分级", 12);

        assertTrue(terms.contains("崩岸"));
        assertTrue(terms.contains("预警"));
        assertTrue(terms.contains("分级"));
        assertFalse(terms.contains("怎么"), "疑问词在停用词表里");
    }

    @Test
    @DisplayName("长词会再切出短词（SEARCH 模式），偏向召回")
    void splitsLongWordsInSearchMode() {
        List<String> terms = ChineseTokenizer.terms("长江中下游崩岸风险评价指标体系", 12);

        assertTrue(terms.contains("崩岸"));
        assertTrue(terms.contains("风险"));
        assertTrue(terms.size() >= 4, "长句应切出多个实词，实际=" + terms);
    }

    @Test
    @DisplayName("同一个词只保留一次（查询侧去重），数量受 maxTerms 限制")
    void deduplicatesAndRespectsMax() {
        List<String> once = ChineseTokenizer.terms("崩岸崩岸崩岸", 12);
        assertEquals(1, once.stream().filter("崩岸"::equals).count());

        List<String> limited = ChineseTokenizer.terms("长江中下游崩岸风险评价指标体系与预警等级划分", 3);
        assertEquals(3, limited.size());
    }

    @Test
    @DisplayName("空输入与非法 maxTerms 返回空列表，不抛异常")
    void handlesBlankInput() {
        assertTrue(ChineseTokenizer.terms(null, 12).isEmpty());
        assertTrue(ChineseTokenizer.terms("   ", 12).isEmpty());
        assertTrue(ChineseTokenizer.terms("崩岸", 0).isEmpty());
    }

    // ---------------- 索引侧 ----------------

    @Test
    @DisplayName("索引侧保留重复词项——ts_rank_cd 要靠词频，去重等于把排序抹平")
    void indexTextKeepsDuplicates() {
        String indexed = ChineseTokenizer.indexText("崩岸崩岸预警");

        assertEquals("崩岸 崩岸 预警", indexed);
    }

    @Test
    @DisplayName("索引侧用空格分隔：PostgreSQL 的 simple 配置否则会把整句当一个词")
    void indexTextIsSpaceSeparated() {
        String indexed = ChineseTokenizer.indexText("崩岸预警分级");

        assertTrue(indexed.contains(" "), "没有空格就没有词边界，中文检索会退化成整句匹配");
        assertFalse(indexed.startsWith(" ") || indexed.endsWith(" "));
    }

    // ---------------- tsquery 拼装 ----------------

    @Test
    @DisplayName("tsquery 用 OR 连接：AND 语义会让长问句的召回塌掉")
    void buildsOrTsQuery() {
        assertEquals("'崩岸' | '预警'", ChineseTokenizer.toTsQuery(List.of("崩岸", "预警")));
        assertEquals("", ChineseTokenizer.toTsQuery(List.of()));
    }

    @Test
    @DisplayName("词项里的单引号被转义，避免拼出语法错误的 tsquery")
    void escapesSingleQuote() {
        assertEquals("'a''b'", ChineseTokenizer.toTsQuery(List.of("a'b")));
    }
}
