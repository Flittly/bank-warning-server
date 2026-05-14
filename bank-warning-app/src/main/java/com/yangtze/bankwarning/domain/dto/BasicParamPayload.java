package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record BasicParamPayload(
        @JsonProperty("param_id") @NotBlank String paramId,
        @JsonProperty("param_name") @NotBlank String paramName,
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
