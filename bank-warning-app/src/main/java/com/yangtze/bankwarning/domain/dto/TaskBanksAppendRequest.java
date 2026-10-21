package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 「把若干岸段并入已有任务」的请求体。
 *
 * <p>{@code mode}：
 * <ul>
 *   <li>{@code merge}（默认）—— 与任务现有 bank_ids 取并集，只增不减。
 *       这是「补充岸段」的语义：老岸段的断面与结果都不该被动到。</li>
 *   <li>{@code replace} —— 整体替换。仅用于需要精确对齐关联集合的场景，
 *       不删除断面，只改关联。</li>
 * </ul>
 *
 * <p>注意：这里**只**改 tasks.bank_ids，不生成断面。断面由前端按
 * {@code POST /v0/bank/sections} 逐条落库，两者分开是为了让"关联"这一步幂等、可重试，
 * 即使断面生成失败，任务关联也不会留下半截状态。
 */
public record TaskBanksAppendRequest(
        @JsonProperty("bank_ids") List<String> bankIds,
        String mode) {
}
