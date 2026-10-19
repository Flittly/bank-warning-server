package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.dto.SearchHitResponse;
import com.yangtze.bankwarning.mapper.SearchMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全局搜索。
 *
 * <p>职责边界：只负责「把一行输入变成一批命中」，不做任何前端状态。
 * 高亮由前端按返回的 terms 自行切分（PG 的 trigram 不提供 token 位置，
 * {@code ts_headline} 对中文又不可用，所以高亮只能在前端做）。
 */
@Service
public class GlobalSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearchService.class);

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    /** 2 字中文会让 GIN trigram 退化成全索引扫描，收紧返回条数避免把退化成本放大 */
    private static final int SHORT_QUERY_LIMIT = 20;

    private final SearchMapper searchMapper;
    private final SearchQueryParser parser;

    public GlobalSearchService(SearchMapper searchMapper, SearchQueryParser parser) {
        this.searchMapper = searchMapper;
        this.parser = parser;
    }

    public Map<String, Object> search(String rawQuery, Integer requestedLimit) {
        SearchQueryParser.ParsedQuery query = parser.parse(rawQuery);

        if (query.pattern() == null) {
            // 只有筛选条件（如 risk:3）时不检索：每段 UNION 都会退化成全表扫描，
            // 而且「只给筛选不给关键词」在搜索框语义下本来就该提示补充输入。
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("success", true);
            empty.put("query", rawQuery == null ? "" : rawQuery);
            empty.put("terms", List.of());
            empty.put("total", 0);
            empty.put("hits", List.of());
            empty.put("degraded", false);
            empty.put("hint", "请输入关键词；可用 risk:3、type:断面 等前缀做筛选");
            return empty;
        }

        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        if (query.shortChinese()) {
            limit = Math.min(limit, SHORT_QUERY_LIMIT);
        }

        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        List<SearchHitResponse> hits;
        try {
            hits = searchMapper.search(
                    query.pattern(),
                    query.exact(),
                    query.prefix(),
                    query.types(),
                    query.riskLevel(),
                    query.taskId(),
                    query.bankId(),
                    userId,
                    limit);
        } catch (RuntimeException e) {
            // 搜索失败不该让整个页面挂掉，返回空结果 + 原因
            log.warn("[search] query failed: {}", e.getMessage());
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("success", false);
            failed.put("query", query.raw());
            failed.put("terms", query.terms());
            failed.put("total", 0);
            failed.put("hits", List.of());
            failed.put("degraded", query.shortChinese());
            failed.put("hint", "检索失败：" + e.getMessage());
            return failed;
        }

        for (SearchHitResponse hit : hits) {
            hit.setMatchedFields(resolveMatchedFields(hit, query.terms()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("query", query.raw());
        body.put("terms", query.terms());
        body.put("total", hits.size());
        body.put("hits", hits);
        body.put("degraded", query.shortChinese());
        // total 是「本次返回条数」而不是全库匹配总数：命中量大时 limit 已截断，
        // 前端据此提示「补充关键词」而不是骗用户「只有 50 条」。
        body.put("truncated", hits.size() >= limit);
        if (query.shortChinese()) {
            body.put("hint", "2 个字的中文查询无法有效利用索引，已限制返回条数；建议再多输入 1 个字");
        }
        if (query.types() != null && !query.types().isEmpty()) {
            body.put("types", query.types());
        }
        if (query.riskLevel() != null) {
            body.put("riskLevel", query.riskLevel());
        }
        return body;
    }

    /** 决定前端在 title 还是 snippet 上画高亮；两边都命中就都高亮 */
    private List<String> resolveMatchedFields(SearchHitResponse hit, List<String> terms) {
        List<String> fields = new ArrayList<>(2);
        String title = hit.getTitle() == null ? "" : hit.getTitle().toLowerCase(Locale.ROOT);
        String snippet = hit.getSnippet() == null ? "" : hit.getSnippet().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            String needle = term.toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && title.contains(needle) && !fields.contains("title")) {
                fields.add("title");
            }
            if (!needle.isEmpty() && snippet.contains(needle) && !fields.contains("snippet")) {
                fields.add("snippet");
            }
        }
        return fields;
    }
}
