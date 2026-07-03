package com.yangtze.bankwarning.domain.dto;

import com.yangtze.bankwarning.domain.po.RiskResultPO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record RiskResultResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("run_id") String runId,
    @JsonProperty("task_id") String taskId,
    @JsonProperty("section_id") String sectionId,
    @JsonProperty("section_name") String sectionName,
    @JsonProperty("region_code") String regionCode,
    @JsonProperty("bank_id") String bankId,
    @JsonProperty("risk_level") Integer riskLevel,
    @JsonProperty("risk_value") Double riskValue,
    @JsonProperty("indicators") Object indicators,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static RiskResultResponse from(RiskResultPO po) {
        if (po == null) return null;
        return new RiskResultResponse(
            po.getId(), po.getRunId(), po.getTaskId(), po.getSectionId(),
            po.getSectionName(), po.getRegionCode(), po.getBankId(),
            po.getRiskLevel(), extractRiskValue(po.getIndicators()),
            JsonUtil.parse(po.getIndicators()),
            po.getCreatedAt(), po.getUpdatedAt()
        );
    }

    private static Double extractRiskValue(String indicatorsJson) {
        Object parsed = JsonUtil.parse(indicatorsJson);
        if (parsed instanceof java.util.Map<?, ?> map) {
            Object result = map.get("result");
            if (result instanceof Number n) return n.doubleValue();
        }
        return null;
    }
}
