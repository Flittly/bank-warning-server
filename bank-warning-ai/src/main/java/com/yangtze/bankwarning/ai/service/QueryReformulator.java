package com.yangtze.bankwarning.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 阶段 5：领域同义词扩展（只作用于关键词路）。
 *
 * <p><b>它解决的是哪一类漏召回</b>：用户问「滩槽高差的阈值是多少」，而资料里写的是
 * {@code Zb}。向量路能跨过这种词面差异（它比的是语义），关键词路只会数「这几个字有没有
 * 出现在这段里」，于是这一路必然空手而归——两路融合就退化成一路的独白。
 *
 * <p><b>词表为什么必须小且经过核对</b>：这里的每一条映射都是从本项目语料里逐个核对出来的
 * （{@code Zb=滩槽高差}、{@code Zd=水位变幅}、{@code Sa=岸坡坡比}、{@code Ln=近岸冲刷}、
 * {@code PQ=造床流量当量}、{@code Dsed=土体组成}、{@code Ky=抗冲流速}、{@code PL=岸坡防护}、
 * {@code LC=荷载控制}）。通用同义词库在这里只会放大噪声：把「高」扩成「大」「多」，
 * 召回量上去了，相关性全没了。
 *
 * <p><b>双向扩展</b>：中文问 → 补标识符（让 ILIKE 能命中代码里的 {@code Zb}）；
 * 标识符问 → 补中文（让倒排索引能命中报告里的「滩槽高差」）。
 *
 * <p><b>默认关闭</b>：OR 扩展只会让召回变宽，不保证变准。开不开由
 * {@code SparseOnlyEvalCli --rewrite} 的对照结果决定，而不是凭感觉。
 */
public final class QueryReformulator {

    /** 中文名 → 标识符。键都来自语料原文，不做近义联想。 */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("滩槽高差", List.of("Zb")),
            Map.entry("水位变幅", List.of("Zd")),
            Map.entry("水位变化", List.of("Zd")),
            Map.entry("岸坡坡比", List.of("Sa")),
            Map.entry("近岸冲刷", List.of("Ln")),
            Map.entry("造床流量", List.of("PQ")),
            Map.entry("土体组成", List.of("Dsed")),
            Map.entry("抗冲流速", List.of("Ky")),
            Map.entry("岸坡防护", List.of("PL")),
            Map.entry("防护等级", List.of("PL")),
            Map.entry("荷载控制", List.of("LC")),
            Map.entry("风险等级", List.of("risk_level")),
            Map.entry("风险阈值", List.of("risk_threshold")),
            // 反向：用户直接写标识符时，补中文，让中文倒排索引也能命中。
            Map.entry("Zb", List.of("滩槽高差")),
            Map.entry("Zd", List.of("水位变幅")),
            Map.entry("Sa", List.of("岸坡坡比")),
            Map.entry("Ln", List.of("近岸冲刷")),
            Map.entry("PQ", List.of("造床流量")),
            Map.entry("Dsed", List.of("土体组成")),
            Map.entry("Ky", List.of("抗冲流速")),
            Map.entry("PL", List.of("岸坡防护")),
            Map.entry("LC", List.of("荷载控制")),
            Map.entry("risk_level", List.of("风险等级")),
            Map.entry("risk_threshold", List.of("风险阈值"))
    );

    private QueryReformulator() {
    }

    /**
     * 取出这次查询可追加的词。
     *
     * <p>匹配是「包含」而不是「相等」：jieba 会把「滩槽高差的阈值」切成
     * 滩槽/高差/的/阈值，单个词项对不上词表键，但对<b>原句</b>做包含判断就能命中。
     *
     * @param normalizedQuery 已过 {@link QueryNormalizer} 的原句
     * @return 去重后的追加词（不做数量截断，由调用方按自己的容量夹）
     */
    public static List<String> expansions(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : SYNONYMS.entrySet()) {
            String key = e.getKey();
            // 中文键按「包含」判断：jieba 会把「滩槽高差的阈值」切开，逐词匹配对不上；
            // ASCII 键按「独立词」判断：否则 "Sa" 会命中 "safety" 这类无关英文串。
            boolean hit = isAscii(key)
                    ? containsToken(lower, key.toLowerCase(Locale.ROOT))
                    : normalizedQuery.contains(key);
            if (hit) {
                out.addAll(e.getValue());
            }
        }
        return new ArrayList<>(out);
    }

    /** 独立词包含判断：命中位置两侧不能是字母或数字。 */
    private static boolean containsToken(String haystack, String needle) {
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean rightOk = end >= haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            idx = end;
        }
        return false;
    }

    /** 纯 ASCII 标识符会走 ILIKE 锚点，其余走中文词项。 */
    public static boolean isIdentifier(String token) {
        return token != null && token.matches("[A-Za-z][A-Za-z0-9_]{1,30}");
    }

    /** 词表规模，供自检/日志使用；同时也是「别把它做成通用词典」的提醒。 */
    public static int size() {
        return SYNONYMS.size();
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
