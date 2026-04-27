package com.yangtze.bankwarning.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TaskStatusUpdateRequest(
        String status,
        @JsonProperty("run_started_at") String runStartedAt,
        @JsonProperty("run_completed_at") String runCompletedAt,
        @JsonProperty("error_message") String errorMessage) {
}
