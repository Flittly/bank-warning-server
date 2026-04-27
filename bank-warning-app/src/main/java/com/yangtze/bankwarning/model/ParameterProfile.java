package com.yangtze.bankwarning.model;

import java.time.Instant;
import java.util.Map;

public record ParameterProfile(
        String profileName,
        String modelApi,
        Map<String, Object> parameters,
        Instant updatedAt) {

    public boolean matches(String targetModelApi) {
        return modelApi == null || modelApi.isBlank() || modelApi.equals(targetModelApi);
    }
}
