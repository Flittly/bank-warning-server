package com.yangtze.bankwarning.ai.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 一次知识检索的留痕记录（阶段 2）。
 *
 * <p>成功与「明确不可用」都会落一行，用来区分「确实没查到」和「知识库坏了」——
 * 阶段 4 的评测与回归门禁要靠它把黑盒变透明。
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
        long elapsedMs) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_UNEXPECTED = "UNEXPECTED";

    /** 命中明细：文档 id、块 id、相似度分数。 */
    public record Hit(String docId, String chunkId, Double score) {}

    private static final Logger log = LoggerFactory.getLogger(RagTrace.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 成功路径。hitDetails 只记最终返回给模型的那几条，不含被权限裁掉的。 */
    public static RagTrace success(String traceId, String scenario, String query, Long userId,
                                   Integer topK, Double scoreThreshold,
                                   int hits, int visible, List<Hit> hitDetails, long elapsedMs) {
        return new RagTrace(traceId, scenario, query, userId, topK, scoreThreshold,
                hits, visible, hitDetails == null ? 0 : hitDetails.size(),
                hitDetails == null ? List.of() : hitDetails,
                STATUS_OK, null, elapsedMs);
    }

    /** 失败/降级路径：hits、visible、returned 均为 0，原因写进 status + degradeReason。 */
    public static RagTrace failure(String traceId, String scenario, String query, Long userId,
                                   Integer topK, Double scoreThreshold,
                                   String status, String reason, long elapsedMs) {
        return new RagTrace(traceId, scenario, query, userId, topK, scoreThreshold,
                0, 0, 0, List.of(), status, reason, elapsedMs);
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
