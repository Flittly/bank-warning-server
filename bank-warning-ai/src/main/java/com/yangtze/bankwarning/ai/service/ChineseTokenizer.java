package com.yangtze.bankwarning.ai.service;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 阶段 3：中文分词与检索词项整理 —— 关键词路的「切词」这一步。
 *
 * <p><b>为什么必须自己切词</b>：PostgreSQL 自带的分词器不认中文词边界，一整句
 * 「崩岸预警怎么分级」会被当成<b>一个</b>词项。索引侧与查询侧都这样切，结果就是
 * 只有原句逐字相同才能命中，等价于没有关键词检索。项目此前正是这个状态：
 * {@code SparseRetriever} 只抽 ASCII 标识符与数字，纯中文提问直接空返回。
 *
 * <p><b>为什么选 jieba</b>：zhparser 需要编译 C 扩展、ParadeDB 要换数据库镜像，
 * 本项目是 Windows 原生 PostgreSQL 部署，两者都落不了地。jieba 是纯 Java、
 * 词典随 jar 分发，无需运维动作。
 *
 * <p><b>索引侧与查询侧的口径刻意不同</b>：
 * <ul>
 *   <li>索引侧 {@link #indexText(String)} 保留<b>重复词项</b>。{@code to_tsvector} 会记录
 *       词频与位置，{@code ts_rank_cd} 靠这些信息判断「这个词在这段里出现得多不多」。
 *       去重等于把词频抹平，排序就退化成「命中/没命中」。</li>
 *   <li>查询侧 {@link #terms(String, int)} 去重并限量。查询词项拼成
 *       {@code '甲' | '乙' | '丙'}，重复词项会让 tsquery 串无意义地变长。</li>
 * </ul>
 *
 * <p><b>用搜索模式（{@code SegMode.SEARCH}）</b>：它把长词再切细
 * （「长江中下游」也切出「长江」「中下游」），偏向召回。检索场景宁可多召回几个候选，
 * 也不要在切词这一步就把正确答案切掉。
 *
 * <p><b>词项白名单</b>：只留汉字、字母、数字、下划线与小数点。tsquery 是有语法的
 * （{@code & | ! ( ) :}），用户提问里出现这些字符若直接拼进去会抛
 * {@code syntax error in tsquery}；先按字符集过滤，再统一加引号，两层都做。
 *
 * <p><b>失败一律降级为空词项</b>，不抛异常：jieba 词典加载失败只会让关键词路退回
 * 「只认 ASCII 锚点」，向量路不受影响。若这里抛异常，整次检索会被降级成 dense-only，
 * 反而更糟。
 */
public final class ChineseTokenizer {

    private static final Logger log = LoggerFactory.getLogger(ChineseTokenizer.class);

    /**
     * 停用词：切出来但检索无信息量的词。
     *
     * <p>只收「高频且几乎出现在每段里」的虚词与疑问词，不做完整停用词表——
     * 过度删词会让「什么是崩岸」这类问题在切词后什么都不剩。
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "及", "或", "这", "那", "有", "我", "你",
            "他", "她", "它", "们", "就", "都", "也", "还", "又", "把", "被", "让", "给",
            "对", "从", "到", "为", "以", "而", "并", "等", "其", "之", "于", "上", "下",
            "什么", "怎么", "怎样", "如何", "哪些", "哪个", "哪里", "是否", "多少", "为什么",
            "可以", "需要", "应该", "请问", "一下", "的话", "一个", "这个", "那个", "这些",
            "那些", "我们", "他们", "它们", "以及", "所以", "因为", "但是", "如果", "或者",
            "the", "and", "for", "are", "was", "were", "with", "this", "that", "what",
            "which", "how", "why", "from", "into", "about", "please", "tell");

    /** 词典加载失败后不再重试（失败一次就会一直失败，重试只会拖慢每次检索）。 */
    private static volatile JiebaSegmenter segmenter;
    private static volatile boolean unavailable;

    private ChineseTokenizer() {
    }

    /**
     * 应用启动时预热：jieba 首次分词要加载约 2 MB 词典（实测 0.6~1.2 s），
     * 不预热就会把这份开销记到第一个提问的用户头上。
     */
    public static void warmUp() {
        long start = System.currentTimeMillis();
        int n = terms("崩岸预警分级标准", 8).size();
        log.info("[rag] jieba 分词预热完成：{} 项，耗时 {} ms", n, System.currentTimeMillis() - start);
    }

    /**
     * 查询侧词项：去重、限量、按字符集白名单过滤。
     *
     * @return 可直接拼进 tsquery 的词项；分词不可用时返回空列表（调用方退回锚点路）
     */
    public static List<String> terms(String text, int maxTerms) {
        if (text == null || text.isBlank() || maxTerms <= 0) {
            return List.of();
        }
        List<String> all = rawTerms(text);
        if (all.isEmpty()) {
            return List.of();
        }
        // 去重但保序：jieba 的输出顺序即「原句出现顺序」，保留它便于排查问题。
        LinkedHashSet<String> uniq = new LinkedHashSet<>(all);
        List<String> out = new ArrayList<>(Math.min(uniq.size(), maxTerms));
        for (String word : uniq) {
            out.add(word);
            if (out.size() >= maxTerms) {
                break;
            }
        }
        return out;
    }

    /**
     * 索引侧正文：全部合法词项、<b>保留重复</b>，空格连接后写入 {@code search_text}。
     *
     * <p>空格是必需的：PostgreSQL 的 {@code simple} 配置按非字母数字边界切词，
     * 连续汉字会被当成一个词项，只有空格分隔才能还原成「词」。
     */
    public static String indexText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return String.join(" ", rawTerms(text));
    }

    /**
     * 把词项拼成 tsquery 表达式：{@code '甲' | '乙' | '丙'}。
     *
     * <p><b>用 OR 而不是 AND</b>：{@code plainto_tsquery} 是 AND 语义，四五个词项全都要
     * 命中才算候选，中文长问句下召回会塌。OR 让「命中部分词项」的段落也进候选，
     * 再由 {@code ts_rank_cd} 按覆盖度排序——这正是 BM25 的做法（布尔筛选 + 打分排序）。
     */
    public static String toTsQuery(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(terms.size() * 8);
        for (String term : terms) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append('\'').append(term.replace("'", "''")).append('\'');
        }
        return sb.toString();
    }

    /** 一段文本的全部合法词项，不去重、不限量。 */
    static List<String> rawTerms(String text) {
        JiebaSegmenter seg = segmenter();
        if (seg == null) {
            return List.of();
        }
        List<SegToken> tokens;
        try {
            tokens = seg.process(text, JiebaSegmenter.SegMode.SEARCH);
        } catch (RuntimeException e) {
            log.warn("[rag] jieba 分词失败，本次只用 ASCII 锚点: {}", e.toString());
            return List.of();
        }
        List<String> out = new ArrayList<>(Math.max(8, text.length() / 2));
        for (SegToken token : tokens) {
            String word = token.word == null ? "" : token.word.trim();
            if (keep(word)) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * 词项准入：过滤停用词、单字汉字、单字符数字/字母，以及含非白名单字符的碎片。
     *
     * <p>单字汉字（「岸」「水」「土」）一律丢弃：它们几乎出现在每一段里，
     * 留在 tsquery 里只会把噪声段一起召回。
     */
    static boolean keep(String word) {
        if (word.isEmpty() || word.length() > 32) {
            return false;
        }
        if (STOP_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean hasHan = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (isHan(c)) {
                hasHan = true;
            } else if (!isAllowedAscii(c)) {
                // 标点、括号、emoji 等：整词丢弃，不做局部裁剪（裁出来的碎片没有语义）。
                return false;
            }
        }
        if (hasHan) {
            return word.length() >= 2;
        }
        // 纯 ASCII：与 SparseRetriever 的锚点口径一致，≥2 字符。
        return word.length() >= 2;
    }

    private static boolean isAllowedAscii(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '.';
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /** 双检锁懒加载；词典缺失时返回 null 并永久标记不可用，避免每次检索都重试。 */
    private static JiebaSegmenter segmenter() {
        if (unavailable) {
            return null;
        }
        JiebaSegmenter local = segmenter;
        if (local != null) {
            return local;
        }
        synchronized (ChineseTokenizer.class) {
            if (unavailable) {
                return null;
            }
            if (segmenter == null) {
                try {
                    segmenter = new JiebaSegmenter();
                } catch (Throwable t) {
                    unavailable = true;
                    log.error("[rag] jieba 词典加载失败，关键词路退回纯 ASCII 锚点: {}", t.toString());
                    return null;
                }
            }
            return segmenter;
        }
    }
}
