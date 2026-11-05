package com.yangtze.bankwarning.domain.dto;

import com.yangtze.bankwarning.domain.po.BasicParamPO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record BasicParamResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("param_id") String paramId,
    @JsonProperty("param_name") String paramName,
    @JsonProperty("segment") String benchSegment,
    @JsonProperty("current_timepoint") String currentTimepoint,
    @JsonProperty("set_name") String setName,
    @JsonProperty("water_qs") String waterQs,
    @JsonProperty("tidal_level") String tidalLevel,
    @JsonProperty("bench_id") String benchId,
    @JsonProperty("ref_id") String refId,
    @JsonProperty("hs") Double hs,
    @JsonProperty("hc") Double hc,
    @JsonProperty("protection_level") String protectionLevel,
    @JsonProperty("control_level") String controlLevel,
    @JsonProperty("comparison_timepoint") String comparisonTimepoint,
    @JsonProperty("risk_thresholds") Object riskThresholds,
    @JsonProperty("weights") Object weights,
    @JsonProperty("other_params") Object otherParams,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("updated_at") LocalDateTime updatedAt,
    /** null = 模板库；非 null = 该任务专属参数集。前端据此决定保存走哪个作用域。 */
    @JsonProperty("task_id") String taskId
) {
    public static BasicParamResponse from(BasicParamPO po) {
        if (po == null) return null;
        return new BasicParamResponse(
            po.getId(), po.getParamId(), po.getParamName(), po.getBenchSegment(),
            po.getCurrentTimepoint(), po.getSetName(), po.getWaterQs(), po.getTidalLevel(),
            po.getBenchId(), po.getRefId(), po.getHs(), po.getHc(),
            po.getProtectionLevel(), po.getControlLevel(), po.getComparisonTimepoint(),
            JsonUtil.parse(po.getRiskThresholds()), JsonUtil.parse(po.getWeights()), JsonUtil.parse(po.getOtherParams()),
            po.getCreatedAt(), po.getUpdatedAt(), po.getTaskId()
        );
    }
}
