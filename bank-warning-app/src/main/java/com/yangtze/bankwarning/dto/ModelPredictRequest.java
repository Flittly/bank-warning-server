package com.yangtze.bankwarning.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record ModelPredictRequest(
        @NotBlank String modelApi,
        Map<String, Object> payload,
        List<String> profileNames,
        Integer timeoutSeconds) {
}
