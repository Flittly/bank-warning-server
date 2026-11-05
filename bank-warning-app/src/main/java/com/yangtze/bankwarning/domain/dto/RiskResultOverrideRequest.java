package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 人工修改风险结果的请求体。
 *
 * `patch` 里的 matrices / tiers 由前端按 riskLevel.py 的同一套规则重算后一并带上。
 * 服务端**不重算**（否则就是第三份评分实现），只校验不变量：
 * bucket(result, all) + 1 必须等于 risk_level，且 PL/LC 必须是合法枚举。
 */
public record RiskResultOverrideRequest(
        @JsonProperty("task_id") String taskId,
        /** 客户端展示的那一行的 id。与库内最新行不一致 ⇒ 409，提示刷新。 */
        @JsonProperty("result_id") Long resultId,
        @JsonProperty("note") String note,
        @JsonProperty("patch") Map<String, Object> patch
) {
}
