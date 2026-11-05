package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 更新「某个任务自己的参数集」的请求体。
 *
 * 与 {@link BasicParamPayload} 的区别：这里不需要 param_id / param_name 必填 ——
 * 参数集由任务唯一确定（param_id 由后端在物化时生成），一次编辑只改值不改归属。
 * 字段全部可空，走 COALESCE 语义（只改传了的字段）。
 */
public record TaskParamsUpdateRequest(
        @JsonProperty("param_name") String paramName,
        @JsonProperty("segment") String benchSegment,
        @JsonProperty("current_timepoint") String currentTimepoint,
        @JsonProperty("set_name") String setName,
        @JsonProperty("water_qs") String waterQs,
        @JsonProperty("tidal_level") String tidalLevel,
        @JsonProperty("bench_id") String benchId,
        @JsonProperty("ref_id") String refId,
        Double hs,
        Double hc,
        @JsonProperty("protection_level") String protectionLevel,
        @JsonProperty("control_level") String controlLevel,
        @JsonProperty("comparison_timepoint") String comparisonTimepoint,
        @JsonProperty("risk_thresholds") Map<String, Object> riskThresholds,
        Map<String, Object> weights,
        @JsonProperty("other_params") Map<String, Object> otherParams) {
}
