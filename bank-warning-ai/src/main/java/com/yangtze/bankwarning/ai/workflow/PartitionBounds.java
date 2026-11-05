package com.yangtze.bankwarning.ai.workflow;

import java.util.Locale;
import java.util.Map;

/**
 * 分区边界判定 / 标签的**唯一实现**。
 *
 * <p>含边规则 {@code west ≤ lng ≤ east 且 south ≤ lat ≤ north} 是跨语言契约：
 * 与 Python {@code visualization/risk_map.py: generate_risk_map_tiles} 统计
 * {@code sections_in_tile} 的口径一致。报告正文、附录、以及随报告导出的
 * 「分区断面详情 CSV」都从这里取值，避免「一处改了另一处没改」导致
 * 「图上写着 5 条、报告里算出 4 条」。
 *
 * <p>自定义分幅出现重叠时，一个断面会同时落进多个分区——所有消费方都照此处理。
 */
public final class PartitionBounds {

    private PartitionBounds() {
    }

    private static Double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 含边判定：断面起点坐标是否落在该分区内。 */
    public static boolean contains(Map<String, Object> partition, double lng, double lat) {
        Double west = doubleOf(partition.get("west"));
        Double south = doubleOf(partition.get("south"));
        Double east = doubleOf(partition.get("east"));
        Double north = doubleOf(partition.get("north"));
        return west != null && south != null && east != null && north != null
                && lng >= west && lng <= east && lat >= south && lat <= north;
    }

    /** 分区标签：「第 r 行第 c 列（经度 a~b，纬度 c~d）」。正文、附录、CSV 共用同一份文案。 */
    public static String label(Map<String, Object> partition) {
        int row = intOf(partition.get("row")) + 1;
        int col = intOf(partition.get("col")) + 1;
        return String.format(Locale.ROOT,
                "第 %d 行第 %d 列（经度 %.4f~%.4f，纬度 %.4f~%.4f）",
                row, col,
                doubleOf(partition.get("west")), doubleOf(partition.get("east")),
                doubleOf(partition.get("south")), doubleOf(partition.get("north")));
    }

    /** 分区键：「r{row}c{col}」，与子图文件名 {@code _r{r}c{c}.png} 的编码一致。 */
    public static String key(Map<String, Object> partition) {
        return "r" + intOf(partition.get("row")) + "c" + intOf(partition.get("col"));
    }
}
