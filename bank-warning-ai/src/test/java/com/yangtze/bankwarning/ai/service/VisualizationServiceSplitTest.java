package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分幅出图配置下发的回归测试。
 *
 * <p>核心约定：
 * <ol>
 *   <li>不分幅（rows=cols=1 且无自定义瓦片）时返回 null —— 载荷里不带 split 键，
 *       Python 走原单图路径，历史行为与旧载荷消费者完全无感；</li>
 *   <li>rows/cols 缺省按 1，非法值（&lt;1）直接报错，不静默纠正；</li>
 *   <li>split_tiles 必须是合法 JSON 数组，原样透传（row/col/边界键由 Python 校验）；</li>
 *   <li>只给 rows/cols 不下发 tiles 键 —— 让 Python 按均分网格生成。</li>
 * </ol>
 */
@DisplayName("分幅出图配置下发")
class VisualizationServiceSplitTest {

    private final VisualizationService service =
            new VisualizationService(null, new ObjectMapper());

    @Test
    @DisplayName("不分幅：返回 null（载荷零感知）")
    void noSplitReturnsNull() {
        assertNull(service.buildSplitConfig(null, null, null));
        assertNull(service.buildSplitConfig(1, 1, null));
        assertNull(service.buildSplitConfig(1, 1, ""));
        assertNull(service.buildSplitConfig(1, 1, "  "));
    }

    @Test
    @DisplayName("2×2 均分：下发 rows/cols，不带 tiles")
    void uniformGridConfig() {
        Map<String, Object> split = service.buildSplitConfig(2, 2, null);
        assertNotNull(split);
        assertEquals(2, split.get("rows"));
        assertEquals(2, split.get("cols"));
        assertFalse(split.containsKey("tiles"));
    }

    @Test
    @DisplayName("单方向分幅：1×3 / 2×1 均合法（只按一个方向切）")
    void singleDirectionSplit() {
        Map<String, Object> byLng = service.buildSplitConfig(1, 3, null);
        assertEquals(1, byLng.get("rows"));
        assertEquals(3, byLng.get("cols"));

        Map<String, Object> byLat = service.buildSplitConfig(2, 1, null);
        assertEquals(2, byLat.get("rows"));
        assertEquals(1, byLat.get("cols"));
    }

    @Test
    @DisplayName("自定义瓦片：合法 JSON 数组原样透传")
    void customTilesPassThrough() {
        String tiles = "[{\"row\":0,\"col\":1,\"west\":119.45}]";
        Map<String, Object> split = service.buildSplitConfig(1, 3, tiles);
        assertNotNull(split);
        Object raw = split.get("tiles");
        assertTrue(raw instanceof List<?>, "tiles 应为 List");
        List<?> list = (List<?>) raw;
        assertEquals(1, list.size());
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals(0, first.get("row"));
        assertEquals(1, first.get("col"));
        assertEquals(119.45, ((Number) first.get("west")).doubleValue());
    }

    @Test
    @DisplayName("只给自定义瓦片也视为分幅（rows/cols 缺省 1）")
    void tilesOnlyIsStillSplit() {
        Map<String, Object> split = service.buildSplitConfig(null, null,
                "[{\"west\":119.2,\"south\":32.2,\"east\":119.4,\"north\":32.4}]");
        assertNotNull(split);
        assertEquals(1, split.get("rows"));
        assertEquals(1, split.get("cols"));
        assertTrue(split.containsKey("tiles"));
    }

    @Test
    @DisplayName("非法分块数与非法 JSON 一律报错，不静默纠正")
    void invalidInputThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSplitConfig(0, 2, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSplitConfig(2, -1, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSplitConfig(2, 2, "not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSplitConfig(2, 2, "{\"rows\":2}"));
    }

    // ------------------------------------------------------------------
    // 批量入口（generateRiskMapBatch）的分幅 fail-fast 与返回项组装
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量入口：分幅参数非法时 fail-fast，不返回 success")
    void batchFailsFastOnInvalidSplit() {
        Map<String, Object> badRows = service.generateRiskMapBatch(
                "task-1", "merged", null, 0, 2, null);
        assertEquals(false, badRows.get("success"));
        assertNotNull(badRows.get("error"));

        Map<String, Object> badTiles = service.generateRiskMapBatch(
                "task-1", "merged", null, 2, 2, "not-json");
        assertEquals(false, badTiles.get("success"));
        assertNotNull(badTiles.get("error"));
    }

    @Test
    @DisplayName("批量入口：taskId 为空仍先报错（历史行为保持）")
    void batchStillValidatesTaskId() {
        Map<String, Object> result = service.generateRiskMapBatch(null, "merged", null);
        assertEquals(false, result.get("success"));
        assertEquals("taskId 不能为空", result.get("error"));
    }

    @Test
    @DisplayName("返回项：单图（未分幅）只有 url，无 tileCount/images")
    void imageEntrySingleMap() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("file_path", "E:/out/viz_x/risk_distribution_map.png");

        Map<String, Object> entry = service.imageEntry(
                null, null, "viz_x", "risk_distribution_map.png", exec);
        assertEquals(true, entry.get("success"));
        assertEquals("/v0/bank/ai/viz-output/viz_x/risk_distribution_map.png", entry.get("url"));
        assertFalse(entry.containsKey("tileCount"), "未分幅不应有 tileCount");
        assertFalse(entry.containsKey("images"), "未分幅不应有 images");
    }

    @Test
    @DisplayName("返回项：单图缺失 file_path 时退回请求文件名（历史兼容）")
    void imageEntrySingleMapLegacyFallback() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);

        Map<String, Object> entry = service.imageEntry(
                "B1", "甲", "viz_x", "risk_map_B1.png", exec);
        assertEquals("/v0/bank/ai/viz-output/viz_x/risk_map_B1.png", entry.get("url"));
    }

    @Test
    @DisplayName("返回项：分幅多图 → tileCount + images[] 明细，url 为首幅")
    void imageEntrySplitTiles() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("file_path", "E:/out/viz_x/m_r0c0.png");
        exec.put("file_paths", List.of(
                "E:/out/viz_x/m_r0c0.png", "E:/out/viz_x/m_r0c1.png"));
        exec.put("tiles", List.of(
                tileInfo("E:/out/viz_x/m_r0c0.png", 0, 0, 5),
                tileInfo("E:/out/viz_x/m_r0c1.png", 0, 1, 4)));

        Map<String, Object> entry = service.imageEntry(
                null, null, "viz_x", "m.png", exec);
        assertEquals(true, entry.get("success"));
        assertEquals(2, entry.get("tileCount"));
        assertEquals("/v0/bank/ai/viz-output/viz_x/m_r0c0.png", entry.get("url"),
                "url 应为第一幅，保证旧前端只读 url 也能展示");

        Object raw = entry.get("images");
        assertTrue(raw instanceof List<?>, "images 应为 List");
        List<?> images = (List<?>) raw;
        assertEquals(2, images.size());
        Map<?, ?> first = (Map<?, ?>) images.get(0);
        assertEquals("m_r0c0.png", first.get("fileName"));
        assertEquals("/v0/bank/ai/viz-output/viz_x/m_r0c0.png", first.get("url"));
        assertEquals(0, first.get("row"));
        assertEquals(0, first.get("col"));
        assertEquals(5, first.get("sections_in_tile"));
        assertTrue(first.get("bbox") instanceof List<?>, "bbox 应透传");
        Map<?, ?> second = (Map<?, ?>) images.get(1);
        assertEquals("m_r0c1.png", second.get("fileName"));
        assertEquals(1, second.get("col"));
        assertEquals(4, second.get("sections_in_tile"));
    }

    @Test
    @DisplayName("返回项：失败时不构造 URL，原样带 error")
    void imageEntryFailure() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", false);
        exec.put("error", "没有风险数据");

        Map<String, Object> entry = service.imageEntry(
                "B1", "甲", "viz_x", "risk_map_B1.png", exec);
        assertEquals(false, entry.get("success"));
        assertEquals("没有风险数据", entry.get("error"));
        assertFalse(entry.containsKey("url"));
    }

    private static Map<String, Object> tileInfo(String filePath, int row, int col,
                                                int sectionsInTile) {
        Map<String, Object> tile = new LinkedHashMap<>();
        tile.put("file_path", filePath);
        tile.put("row", row);
        tile.put("col", col);
        tile.put("bbox", List.of(119.2, 32.1, 119.4, 32.3));
        tile.put("sections_in_tile", sectionsInTile);
        return tile;
    }
}
