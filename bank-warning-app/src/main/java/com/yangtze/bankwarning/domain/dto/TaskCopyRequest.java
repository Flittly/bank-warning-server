package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 复制任务的请求体。**所有字段都可选**，空 body（或 `{}`）即「按默认策略复制配置」。
 *
 * @param taskName        新任务名；缺省 = 原名称 + ` - 副本`，超 100 字符由服务端按后缀优先截断
 * @param includeResults  是否连 {@code bank_risk_results} / {@code section_profiles} 一起复制。
 *                        缺省 false —— 结果可由同一份输入确定性重算（模型 case 缓存命中），
 *                        而搬结果会让「这批结论是谁算的、谁改的」脱离上下文
 * @param includeParams   是否复制任务私有参数集（{@code basic_params.task_id} 非空那行）。
 *                        缺省 true；置 false 则副本首次打开参数面板时才按默认模板物化，等于「只要骨架」
 * @param resetValidation 是否把断面的校验结论打回 pending。缺省 false：几何与参数一字未改，
 *                        重跑校验只会得到同一结论，重置反而让资源树整片「未校验」
 * @param targetTaskId    指定新任务 id，仅用于**幂等重试**；缺省由服务端生成 {@code task-<uuid>}
 * @param targetUserId    跨用户复制的目标用户，P1 能力。当前仅允许等于当前登录用户，否则 403
 */
public record TaskCopyRequest(
        @JsonProperty("task_name") String taskName,
        @JsonProperty("include_results") Boolean includeResults,
        @JsonProperty("include_params") Boolean includeParams,
        @JsonProperty("reset_validation") Boolean resetValidation,
        @JsonProperty("target_task_id") String targetTaskId,
        @JsonProperty("target_user_id") Long targetUserId) {
}
