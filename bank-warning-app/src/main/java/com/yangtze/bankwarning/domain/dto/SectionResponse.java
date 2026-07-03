package com.yangtze.bankwarning.domain.dto;

import com.yangtze.bankwarning.domain.po.SectionPO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record SectionResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("task_id") String taskId,
    @JsonProperty("section_id") String sectionId,
    @JsonProperty("section_name") String sectionName,
    @JsonProperty("bank_id") String bankId,
    @JsonProperty("region_code") String regionCode,
    @JsonProperty("segment_index") Integer segmentIndex,
    @JsonProperty("section_geometry") Object sectionGeometry,
    @JsonProperty("vertical_foot_point") Object verticalFootPoint,
    @JsonProperty("distance") Double distance,
    @JsonProperty("basic_param_id") Integer basicParamId,
    @JsonProperty("param_name") String paramName,
    @JsonProperty("segment") String segment,
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
    @JsonProperty("is_valid") Boolean isValid,
    @JsonProperty("validation_status") String validationStatus,
    @JsonProperty("validation_message") String validationMessage,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static SectionResponse from(SectionPO po) {
        if (po == null) return null;
        return new SectionResponse(
            po.getId(), po.getTaskId(), po.getSectionId(), po.getSectionName(),
            po.getBankId(), po.getRegionCode(), po.getSegmentIndex(),
            JsonUtil.parse(po.getSectionGeometry()), JsonUtil.parse(po.getVerticalFootPoint()),
            po.getDistance(), po.getBasicParamId(), po.getParamName(),
            po.getSegment(), po.getCurrentTimepoint(), po.getSetName(),
            po.getWaterQs(), po.getTidalLevel(), po.getBenchId(), po.getRefId(),
            po.getHs(), po.getHc(), po.getProtectionLevel(), po.getControlLevel(),
            po.getComparisonTimepoint(),
            JsonUtil.parse(po.getRiskThresholds()), JsonUtil.parse(po.getWeights()), JsonUtil.parse(po.getOtherParams()),
            po.getIsValid(), po.getValidationStatus(), po.getValidationMessage(),
            po.getCreatedAt(), po.getUpdatedAt()
        );
    }
}
