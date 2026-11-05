package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 复制任务的结果。
 *
 * <p>{@code counts} 是「实际落库的行数」，不是估算：断面数来自 `INSERT … SELECT` 的返回行数。
 * 幂等重试（同一 {@code target_task_id} 第二次调用）返回的是库里现有行数，且 {@code idempotent=true}。
 *
 * <p>{@code warnings} 里的每一条都是**不阻断复制**的信息，前端必须原样展示：
 * 源任务在跑、参数是现物化的、某些岸段/引用已失效……这些事用户不看就不知道副本为什么不完整。
 */
public record TaskCopyResponse(
        boolean success,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("task_name") String taskName,
        @JsonProperty("source_task_id") String sourceTaskId,
        Map<String, Integer> counts,
        List<Warning> warnings,
        boolean idempotent,
        @JsonProperty("elapsed_ms") long elapsedMs) {

    /** 单个提示项。{@code code} 供前端分支（例如 SOURCE_RUNNING 要标红），{@code message} 直接展示。 */
    public record Warning(String code, String message) {
    }
}
