package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 岸段几何解析的回归测试。
 *
 * <p>本次线上问题的根因：{@code banks.bank_geometry} 是 jsonb 列，经 JdbcTemplate 取回时是
 * {@link PGobject} 而不是 String，旧实现只认 String/Map，于是所有岸段被判为「无有效几何」
 * 而全部跳过 —— 成图里完全没有岸段。因此这里把「四种取回形态」逐一钉死。
 */
@DisplayName("岸段几何解析")
class VisualizationServiceGeometryTest {

    private static final String LINE_JSON =
            "{\"type\":\"LineString\",\"coordinates\":"
            + "[[119.248,32.223],[119.247,32.230],[119.256,32.231]]}";

    private final VisualizationService service =
            new VisualizationService(null, new ObjectMapper());

    private static PGobject pgJsonb(String json) throws Exception {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue(json);
        return pg;
    }

    @Test
    @DisplayName("PGobject（jsonb 的真实取回类型）必须能解析")
    void pgObjectIsParsed() throws Exception {
        List<List<Double>> coords = service.extractLineStringCoords(pgJsonb(LINE_JSON));

        assertNotNull(coords, "PGobject 未被识别，岸段会被静默丢弃（本次线上故障）");
        assertEquals(3, coords.size());
        assertEquals(119.248, coords.get(0).get(0), 1e-9);
        assertEquals(32.223, coords.get(0).get(1), 1e-9);
    }

    @Test
    @DisplayName("JSON 字符串能解析")
    void jsonStringIsParsed() {
        List<List<Double>> coords = service.extractLineStringCoords(LINE_JSON);

        assertNotNull(coords);
        assertEquals(3, coords.size());
    }

    @Test
    @DisplayName("已解析成对象的 Map 能解析")
    void parsedMapIsAccepted() throws Exception {
        Map<?, ?> parsed = new ObjectMapper().readValue(LINE_JSON, Map.class);

        List<List<Double>> coords = service.extractLineStringCoords(parsed);

        assertNotNull(coords);
        assertEquals(3, coords.size());
    }

    @Test
    @DisplayName("byte[] 能解析")
    void byteArrayIsDecoded() {
        List<List<Double>> coords =
                service.extractLineStringCoords(LINE_JSON.getBytes(StandardCharsets.UTF_8));

        assertNotNull(coords);
        assertEquals(3, coords.size());
    }

    @Test
    @DisplayName("GeoJSON Feature 包裹能被拆开")
    void geojsonFeatureIsUnwrapped() {
        String feature = "{\"type\":\"Feature\",\"properties\":{},"
                + "\"geometry\":" + LINE_JSON + "}";

        List<List<Double>> coords = service.extractLineStringCoords(feature);

        assertNotNull(coords, "Feature 形态未兼容");
        assertEquals(3, coords.size());
    }

    @Test
    @DisplayName("空值与空文本返回 null（而不是抛异常）")
    void blankAndNullReturnNull() throws Exception {
        assertNull(service.extractLineStringCoords(null));
        assertNull(service.extractLineStringCoords(""));
        assertNull(service.extractLineStringCoords("   "));
        assertNull(service.extractLineStringCoords(pgJsonb("")));
    }

    @Test
    @DisplayName("非 LineString 类型返回 null")
    void nonLineStringReturnsNull() {
        String polygon = "{\"type\":\"Polygon\",\"coordinates\":"
                + "[[[119.0,32.0],[119.1,32.0],[119.1,32.1],[119.0,32.0]]]}";

        assertNull(service.extractLineStringCoords(polygon));
    }

    @Test
    @DisplayName("少于 2 个点视为无效（画不出折线）")
    void singlePointReturnsNull() {
        String single = "{\"type\":\"LineString\",\"coordinates\":[[119.248,32.223]]}";

        assertNull(service.extractLineStringCoords(single));
    }

    @Test
    @DisplayName("个别非法坐标点被跳过，其余仍可用")
    void nonNumericCoordinatesAreSkipped() {
        String messy = "{\"type\":\"LineString\",\"coordinates\":"
                + "[[119.248,32.223],[\"x\",null],[119.256,32.231]]}";

        List<List<Double>> coords = service.extractLineStringCoords(messy);

        assertNotNull(coords);
        assertEquals(2, coords.size());
    }

    @Test
    @DisplayName("畸形 JSON 返回 null 而不抛异常")
    void malformedJsonReturnsNull() {
        assertNull(service.extractLineStringCoords("{not json"));
    }
}
