package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

public record TaskPayload(
        @JsonProperty("task_id") @NotBlank String taskId,
        @JsonProperty("task_name") @NotBlank String taskName,
        @JsonProperty("bank_ids") List<String> bankIds,
        String description) {
}
