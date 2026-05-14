package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SectionPayload(
        @JsonProperty("section_id") @NotBlank String sectionId,
        @JsonProperty("section_name") @NotBlank String sectionName,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("bank_id") @NotBlank String bankId,
        @JsonProperty("region_code") @NotBlank String regionCode,
        @JsonProperty("segment_index") Integer segmentIndex,
        Map<String, Object> geometry,
        @JsonProperty("section_geometry") Map<String, Object> sectionGeometry,
        @JsonProperty("vertical_foot_point") Map<String, Object> verticalFootPoint,
        Double distance,
        @JsonProperty("basic_param_id") Integer basicParamId,
        @JsonProperty("param_name") String paramName,
        String segment,
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
