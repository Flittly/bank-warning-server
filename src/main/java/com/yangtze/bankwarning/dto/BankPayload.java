package com.yangtze.bankwarning.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record BankPayload(
        @JsonProperty("bank_id") @NotBlank String bankId,
        @JsonProperty("bank_name") @NotBlank String bankName,
        @JsonProperty("region_code") @NotBlank String regionCode,
        Map<String, Object> geometry,
        @JsonProperty("bank_geometry") Map<String, Object> bankGeometry,
        String description,
        Boolean reversed) {
}
