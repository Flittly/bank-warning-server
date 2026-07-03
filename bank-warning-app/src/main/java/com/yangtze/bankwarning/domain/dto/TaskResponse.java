package com.yangtze.bankwarning.domain.dto;

import com.yangtze.bankwarning.domain.po.TaskPO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record TaskResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("task_id") String taskId,
    @JsonProperty("task_name") String taskName,
    @JsonProperty("bank_ids") Object bankIds,
    @JsonProperty("description") String description,
    @JsonProperty("status") String status,
    @JsonProperty("run_started_at") LocalDateTime runStartedAt,
    @JsonProperty("run_completed_at") LocalDateTime runCompletedAt,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static TaskResponse from(TaskPO po) {
        if (po == null) return null;
        return new TaskResponse(
            po.getId(), po.getTaskId(), po.getTaskName(),
            JsonUtil.parse(po.getBankIds()), po.getDescription(), po.getStatus(),
            po.getRunStartedAt(), po.getRunCompletedAt(),
            po.getCreatedAt(), po.getUpdatedAt()
        );
    }
}
