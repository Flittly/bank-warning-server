package com.yangtze.bankwarning.domain.dto;

import com.yangtze.bankwarning.domain.po.RiskResultPO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Map;

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
    @JsonProperty("updated_at") LocalDateTime updatedAt,
    /** 是否被人工修改过。为 true 时前端在弹窗/popup 上打「已人工修改」角标。 */
    @JsonProperty("edited") boolean edited,
    @JsonProperty("edited_at") String editedAt,
    @JsonProperty("edit_note") String editNote
) {
    public static RiskResultResponse from(RiskResultPO po) {
        if (po == null) return null;
        Object editState = JsonUtil.parse(po.getEditState());
        String editedAt = null;
        String editNote = null;
        boolean edited = false;
        if (editState instanceof Map<?, ?> state) {
            edited = true;
            Object at = state.get("edited_at");
            Object note = state.get("note");
            editedAt = at == null ? null : String.valueOf(at);
            editNote = note == null ? null : String.valueOf(note);
        }
        return new RiskResultResponse(
            po.getId(), po.getRunId(), po.getTaskId(), po.getSectionId(),
            po.getSectionName(), po.getRegionCode(), po.getBankId(),
            po.getRiskLevel(), extractRiskValue(po.getIndicators()),
            JsonUtil.parse(po.getIndicators()),
            po.getCreatedAt(), po.getUpdatedAt(),
            edited, editedAt, editNote
        );
    }

    private static Double extractRiskValue(String indicatorsJson) {
        Object parsed = JsonUtil.parse(indicatorsJson);
        if (parsed instanceof Map<?, ?> map) {
            Object result = map.get("result");
            if (result instanceof Number n) return n.doubleValue();
        }
        return null;
    }
}
