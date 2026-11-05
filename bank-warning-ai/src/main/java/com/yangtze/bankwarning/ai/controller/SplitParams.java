package com.yangtze.bankwarning.ai.controller;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求体里分幅参数（splitRows / splitCols / splitTiles）的解析。
 *
 * <p>出图（{@code /viz/risk-map/batch}）与出报告（{@code /report/task/{id}}）
 * 共用同一份解析，避免两处语义漂移。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code null} / 空串 = 「未提供」，保持历史行为（不分幅）；</li>
 *   <li>纯数字字符串也接受（前端表单可能提交字符串而非数字）；</li>
 *   <li>解析不了就**抛错，不静默按 1 纠正**——静默纠正会让用户以为分了幅，
 *       实际只出了一张图，且报告里也不会出现分区。</li>
 * </ul>
 */
final class SplitParams {

    private SplitParams() {
    }

    /** 解析整数参数：null / 空串按「未提供」处理；无法解析时抛 {@link IllegalArgumentException}。 */
    static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("splitRows/splitCols 必须是整数，得到: " + text);
        }
    }

    /** null 安全转字符串；null 保持 null（区分「未提供」与「空串」由调用方按需处理）。 */
    static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 字符串数组参数（如 bankIds）：非数组返回空列表，null 项与空白项丢弃。 */
    static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                out.add(String.valueOf(item).trim());
            }
        }
        return out;
    }
}
