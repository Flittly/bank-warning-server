package com.yangtze.bankwarning.domain.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record ModelParameterUpdateRequest(
        @NotBlank String profileName,
        String modelApi,
        Boolean merge,
        Map<String, Object> parameters) {
}
