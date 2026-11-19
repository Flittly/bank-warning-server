package com.yangtze.bankwarning.ai.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 一次知识检索的留痕记录（阶段 2 建立，阶段 5 补齐档位与空返回原因）。
 *
 * <p>成功与「明确不可用」都会落一行，用来区分「确实没查到」和「知识库坏了」——
 * 评测与回归门禁要靠它把黑盒变透明。
 *
 * <p><b>阶段 5 新增两列</b>：
 * <ul>
 *   <li>{@code mode}：本次生效档位（{@code dense / dense+rerank / hybrid / hybrid+rerank}）。
 *       没有它，同一份 query 在合闸前后两次检索的分数不可比，事后也无从判断
 *       「变差是因为改了阈值，还是因为档位变了」；</li>
 *   <li>{@code emptyReason}：成功但返回 0 条时的原因，见 {@link RagEmptyReason}。
 *       空列表的四种成因要做的事完全不同，混成一个空数组等于把问题藏起来。</li>
 * </ul>
 *
 * <p>刻意不依赖 Spring 容器：静态 ObjectMapper 只做只读序列化，
 * 序列化失败降级成 {@code []}，绝不影响检索本身。
 */
public record RagTrace(
        String traceId,
        String scenario,
        String query,
        Long userId,
        Integer topK,
        Double scoreThreshold,
        int hits,
        int visible,
        int returned,
        List<Hit> hitDetails,
        String status,
        String degradeReason,
        long elapsedMs,
        String mode,
        String emptyReason) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_UNEXPECTED = "UNEXPECTED";

    /** 命中明细：文档 id、块 id、相似度分数。 */
    public record Hit(String docId, String chunkId, Double score) {}

    private static final Logger log = LoggerFactory.getLogger(RagTrace.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 成功路径（阶段 5 之前的口径，{@code mode}/{@code emptyReason} 留空）。
     * 保留是为了不让既有调用点被迫改参数：档位信息缺省为 null 只影响可读性，不影响检索。
     */
    public static RagTrace success(String traceId, String scenario, String query, Long userId,
                                   Integer topK, Double scoreThreshold,
                                   int hits, int visible, List<Hit> hitDetails, long elapsedMs) {
        return success(traceId, scenario, query, userId, topK, scoreThreshold,
                hits, visible, hitDetails, elapsedMs, null, null);
    }

    /** 成功路径：hitDetails 只记最终返回给模型的那几条，不含被权限裁掉的。 */
    public static RagTrace success(String traceId, String scenario, String query, Long userId,
                                   Integer topK, Double scoreThreshold,
                                   int hits, int visible, List<Hit> hitDetails, long elapsedMs,
                                   String mode, String emptyReason) {
        boolean empty = hitDetails == null || hitDetails.isEmpty();
        return new RagTrace(traceId, scenario, query, userId, topK, scoreThreshold,
                hits, visible, empty ? 0 : hitDetails.size(),
                empty ? List.of() : hitDetails,
                STATUS_OK, null, elapsedMs, mode,
                // 有返回结果时原因必须为空：两者同时存在说明统计口径写错了，
                // 让脏数据显式消失，比在报表里出现「命中 3 条且原因为空候选」更好。
                empty ? emptyReason : null);
    }

    /** 失败/降级路径：hits、visible、returned 均为 0，原因写进 status + degradeReason。 */
    public static RagTrace failure(String traceId, String scenario, String query, Long userId,
                                   Integer topK, Double scoreThreshold,
                                   String status, String reason, long elapsedMs) {
        return failure(traceId, scenario, query, userId, topK, scoreThreshold,
                status, reason, elapsedMs, null);
    }

    public static RagTrace failure(String traceId, String scenario, String query, Long userId,
                                   Integer topK, Double scoreThreshold,
                                   String status, String reason, long elapsedMs, String mode) {
        return new RagTrace(traceId, scenario, query, userId, topK, scoreThreshold,
                0, 0, 0, List.of(), status, reason, elapsedMs, mode, null);
    }

    /** 序列化成 ai_rag_traces.hits_json；失败时返回 "[]"，不抛异常。 */
    public String hitsJson() {
        if (hitDetails == null || hitDetails.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(hitDetails);
        } catch (Exception e) {
            log.warn("[rag] 命中明细序列化失败，落库为空数组: {}", e.getMessage());
            return "[]";
        }
    }
}
