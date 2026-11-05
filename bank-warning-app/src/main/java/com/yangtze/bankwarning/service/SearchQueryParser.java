package com.yangtze.bankwarning.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把搜索框里的一行输入拆成「自由词 + 结构化筛选」。
 *
 * <p>存在的理由：本项目库里文本熵极低（「岸线分析任务」重名 10+ 条、「断面72」这种模板名），
 * 纯关键词检索必然被同名结果淹没。把 {@code risk:3}、{@code type:断面} 这类 token
 * 单独抽出来走结构化条件，比硬拼相关度排序有效得多。
 *
 * <p>另一件事：中文 2 字查询会让 GIN trigram 退化成全索引扫描（实测 9ms，比 seq scan 的 7.9ms 还慢）。
 * 这里把它标出来，由调用方收紧 limit 并在响应里提示，而不是假装没这回事。
 */
@Component
public class SearchQueryParser {

    /** 结构化筛选前缀 -> 归一化字段名 */
    private static final Map<String, String> FILTER_ALIASES = Map.ofEntries(
            Map.entry("type", "type"),
            Map.entry("kind", "type"),
            Map.entry("risk", "risk"),
            Map.entry("risk_level", "risk"),
            Map.entry("task", "task"),
            Map.entry("bank", "bank"),
            Map.entry("dem", "dem")
    );

    /** 中文别名 -> 后端 kind */
    private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
            Map.entry("任务", "task"),
            Map.entry("岸段", "bank"),
            Map.entry("断面", "section"),
            Map.entry("剖面", "section"),
            Map.entry("备注", "note"),
            Map.entry("报告", "report"),
            Map.entry("dem", "dem"),
            Map.entry("资源", "dem")
    );

    private static final Set<String> VALID_KINDS = Set.of("task", "bank", "section", "dem", "note", "report");

    public record ParsedQuery(
            String raw,
            List<String> terms,
            /** ILIKE 模式 {@code %a%b%}；无自由词时为 null */
            String pattern,
            /** 完整输入的等值比较用（精确命中权重 3.0） */
            String exact,
            /** 首个词的前缀模式，用于权重 2.0 */
            String prefix,
            List<String> types,
            Integer riskLevel,
            String taskId,
            String bankId,
            String demKey,
            /** 任一词是 2 字中文 —— 索引会退化，调用方应收紧 limit */
            boolean shortChinese
    ) {
    }

    public ParsedQuery parse(String rawQuery) {
        if (rawQuery == null) {
            return empty(null);
        }
        String raw = rawQuery.trim();
        if (raw.isEmpty()) {
            return empty(null);
        }

        List<String> terms = new ArrayList<>();
        Map<String, String> filters = new LinkedHashMap<>();

        for (String token : raw.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            int colon = token.indexOf(':');
            if (colon > 0 && colon < token.length() - 1) {
                String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = token.substring(colon + 1).trim();
                String canonical = FILTER_ALIASES.get(key);
                if (canonical != null && !value.isEmpty()) {
                    filters.put(canonical, value);
                    continue;
                }
            }
            terms.add(token);
        }

        if (terms.isEmpty()) {
            // 只有筛选条件时不做文本检索：否则每一段 UNION 都会返回全表。
            // 前端会提示「请输入关键词」，这是有意的，不是缺陷。
            return new ParsedQuery(raw, List.of(), null, null, null,
                    parseTypes(filters.get("type")), parseRisk(filters.get("risk")),
                    filters.get("task"), filters.get("bank"), filters.get("dem"), false);
        }

        String pattern = "%" + String.join("%", terms.stream().map(SearchQueryParser::escapeLike).toList()) + "%";
        String first = terms.get(0);
        boolean shortChinese = terms.stream().anyMatch(SearchQueryParser::isShortChinese);

        return new ParsedQuery(
                raw,
                List.copyOf(terms),
                pattern,
                terms.size() == 1 ? first : null,
                escapeLike(first) + "%",
                parseTypes(filters.get("type")),
                parseRisk(filters.get("risk")),
                filters.get("task"),
                filters.get("bank"),
                filters.get("dem"),
                shortChinese
        );
    }

    private static ParsedQuery empty(String raw) {
        return new ParsedQuery(raw, List.of(), null, null, null, List.of(), null, null, null, null, false);
    }

    private static List<String> parseTypes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split("[,，]")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String kind = TYPE_ALIASES.getOrDefault(p.toLowerCase(Locale.ROOT), p.toLowerCase(Locale.ROOT));
            if (VALID_KINDS.contains(kind) && !out.contains(kind)) {
                out.add(kind);
            }
        }
        return List.copyOf(out);
    }

    private static Integer parseRisk(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int level = Integer.parseInt(value.trim());
            return level >= 1 && level <= 4 ? level : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** LIKE 通配符转义：用户输入 {@code 100%} 时不能把 % 当通配符 */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isShortChinese(String term) {
        if (term == null || term.isEmpty()) {
            return false;
        }
        int codePoints = term.codePointCount(0, term.length());
        if (codePoints >= 3) {
            return false;
        }
        return term.codePoints().allMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }
}
