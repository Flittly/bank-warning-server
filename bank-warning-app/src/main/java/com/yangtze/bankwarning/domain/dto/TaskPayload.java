package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 单个任务的创建载荷。
 *
 * {@code taskId} 由**前端**生成（UUID，见 bank-warning-web/src/utils/taskId.ts），
 * 前端拿它命名 bankId / sectionId（`${taskId}-...` / `sec-${taskId}-...`），因此这里必须
 * 由前端传入、后端透传存储。历史上前端用 {@code task-${Date.now()}} 存在碰撞/可预测问题，
 * 已改为 {@code task-<uuid>}。两个字段都必填。
 */
public record TaskPayload(
        @JsonProperty("task_id") @NotBlank String taskId,
        @JsonProperty("task_name") @NotBlank String taskName,
        @JsonProperty("bank_ids") List<String> bankIds,
        String description) {
}
