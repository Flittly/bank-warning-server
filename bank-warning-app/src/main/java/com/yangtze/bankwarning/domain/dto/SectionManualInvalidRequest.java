package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 批量把断面标记为「无效」/「恢复有效」。
 *
 * <p>{@code invalid} 用 {@link Boolean} 而非 {@code boolean}：JSON 里漏传字段时
 * {@code boolean} 会静默变成 {@code false} —— 那正好是「恢复有效」，
 * 于是一个写错的请求会把用户刚标好的无效断面全部复活。这里保留 null，
 * 由 service 显式报 400，让错误暴露在请求层而不是数据层。
 *
 * @param sectionIds 断面业务 id（`section_id`），不是自增主键
 * @param invalid    true = 标记无效；false = 恢复有效
 * @param reason     标记原因（选填，≤200 字）；恢复有效时忽略
 */
public record SectionManualInvalidRequest(
        @JsonProperty("section_ids") List<String> sectionIds,
        @JsonProperty("invalid") Boolean invalid,
        @JsonProperty("reason") String reason
) {}
