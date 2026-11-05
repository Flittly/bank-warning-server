package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code section_profiles.profile_data} 读取口径的回归测试。
 *
 * <p>线上问题：写入端（{@code SectionProfileService#buildProfileData}）存的是
 * {@code profile:[{index,distance,x,y,elevation}]}，而 {@code VisualizationService}
 * 读的是 {@code points:[[x,y,elevation]]}。键名与元素形态都不匹配 ⇒ 取到 null ⇒
 * {@code /viz/heatmap} 与 {@code /viz/section} 恒回「无法构建网格数据 / 断面数据」，
 * AI 报告里的冲淤热力图、断面对比图全部拿不到图，且**不抛异常、只留一行 warn**。
 *
 * <p>这里把三条约束钉死：
 * <ol>
 *   <li>现行 {@code profile} 形态必须能读出 (distance, elevation)；</li>
 *   <li>jsonb 以 {@link PGobject} 取回时不能丢（与岸段几何同一个坑）；</li>
 *   <li>早期 {@code points} 形态仍要兼容，老任务回放不能失效。</li>
 * </ol>
 */
@DisplayName("断面剖面数据的读取口径")
class VisualizationServiceProfileDataTest {

    private static final String PROFILE_DATA_JSON = """
            {
              "profile": [
                {"index": 0, "distance": 0.0,  "x": 119.10, "y": 32.20, "elevation": 4.50},
                {"index": 1, "distance": 20.0, "x": 119.11, "y": 32.21, "elevation": 2.10},
                {"index": 2, "distance": 40.0, "x": 119.12, "y": 32.22, "elevation": -8.30}
              ],
              "interval": 20.0,
              "deepest_index": 2,
              "slope_foot_index": 1,
              "points_v": [],
              "Sa_v": []
            }
            """;

    private final VisualizationService service = new VisualizationService(null, new ObjectMapper());

    private static PGobject pgJsonb(String json) throws Exception {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue(json);
        return pg;
    }

    private static Map<String, Object> row(Object profileData) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section_id", "S-0001");
        row.put("section_name", "S-0001");
        row.put("profile_data", profileData);
        return row;
    }

    @Test
    @DisplayName("现行 profile 形态：键名是 profile，元素是 dict")
    void currentProfileShapeIsRead() throws Exception {
        Map<String, Object> parsed = service.readProfileData(PROFILE_DATA_JSON);
        assertNotNull(parsed, "profile_data 未被解析");

        List<VisualizationService.ProfileSample> samples =
                VisualizationService.extractProfileSamples(parsed);

        assertEquals(3, samples.size(), "profile 应读出 3 个采样点");
        assertEquals(0.0, samples.get(0).distance(), 1e-9);
        assertEquals(4.50, samples.get(0).elevation(), 1e-9);
        assertEquals(40.0, samples.get(2).distance(), 1e-9);
        assertEquals(-8.30, samples.get(2).elevation(), 1e-9);
    }

    @Test
    @DisplayName("jsonb 以 PGobject 取回时不能丢行")
    void pgObjectProfileDataIsParsed() throws Exception {
        Map<String, Object> parsed = service.readProfileData(pgJsonb(PROFILE_DATA_JSON));
        assertNotNull(parsed, "PGobject 形态被丢弃，剖面会整批消失");
        assertEquals(3, VisualizationService.extractProfileSamples(parsed).size());
    }

    @Test
    @DisplayName("早期 points 形态仍兼容：按 interval × 下标反推距离")
    void legacyPointsShapeStillWorks() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("interval", 10.0);
        legacy.put("points", List.of(
                List.of(119.10, 32.20, 5.0),
                List.of(119.11, 32.21, 1.0)));

        List<VisualizationService.ProfileSample> samples =
                VisualizationService.extractProfileSamples(legacy);

        assertEquals(2, samples.size());
        assertEquals(0.0, samples.get(0).distance(), 1e-9);
        assertEquals(10.0, samples.get(1).distance(), 1e-9);
        assertEquals(5.0, samples.get(0).elevation(), 1e-9);
    }

    @Test
    @DisplayName("缺 distance 时回退到 interval × index，而不是给 0")
    void distanceFallsBackToIntervalTimesIndex() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interval", 25.0);
        data.put("profile", List.of(
                Map.of("index", 0, "elevation", 3.0),
                Map.of("index", 3, "elevation", 2.0)));

        List<VisualizationService.ProfileSample> samples =
                VisualizationService.extractProfileSamples(data);

        assertEquals(2, samples.size());
        assertEquals(0.0, samples.get(0).distance(), 1e-9);
        assertEquals(75.0, samples.get(1).distance(), 1e-9);
    }

    @Test
    @DisplayName("高程缺失或非有限值的点被丢弃，不产生 NaN 行")
    void invalidElevationsAreDropped() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", List.of(
                Map.of("index", 0, "elevation", 3.0),
                Map.of("index", 1),
                Map.of("index", 2, "elevation", "NaN"),
                Map.of("index", 3, "elevation", 2.0)));

        List<VisualizationService.ProfileSample> samples =
                VisualizationService.extractProfileSamples(data);

        assertEquals(2, samples.size(), "无高程 / NaN 的点必须被丢弃");
        assertEquals(3.0, samples.get(0).elevation(), 1e-9);
        assertEquals(2.0, samples.get(1).elevation(), 1e-9);
    }

    @Test
    @DisplayName("空 / 脏 profile_data 返回空序列而不是抛异常")
    void emptyOrBrokenProfileDataIsTolerated() {
        assertNull(service.readProfileData(null));
        assertNull(service.readProfileData("   "));
        assertNull(service.readProfileData("{not json"));
        assertTrue(VisualizationService.extractProfileSamples(null).isEmpty());
        assertTrue(VisualizationService.extractProfileSamples(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("不等长断面必须拉齐，否则 numpy 2.x 抛 inhomogeneous shape")
    void raggedSeriesAreResampledToUniformLength() {
        List<List<Double>> ragged = new ArrayList<>();
        ragged.add(new ArrayList<>(List.of(1.0, 2.0, 3.0, 4.0, 5.0)));
        ragged.add(new ArrayList<>(List.of(10.0, 20.0)));

        List<List<Double>> uniform = VisualizationService.resampleToUniformLength(ragged);

        assertEquals(2, uniform.size());
        assertEquals(5, uniform.get(0).size());
        assertEquals(5, uniform.get(1).size(), "短行必须补到与最长行一致");
        assertEquals(10.0, uniform.get(1).get(0), 1e-9, "首点保留");
        assertEquals(20.0, uniform.get(1).get(4), 1e-9, "末点保留");
        assertEquals(15.0, uniform.get(1).get(2), 1e-9, "中间线性插值");
    }

    @Test
    @DisplayName("单点断面拉齐时退化复制，不越界")
    void singlePointRowDoesNotThrow() {
        List<List<Double>> series = new ArrayList<>();
        series.add(new ArrayList<>(List.of(1.0, 2.0, 3.0)));
        series.add(new ArrayList<>(List.of(7.0)));

        List<List<Double>> uniform = VisualizationService.resampleToUniformLength(series);

        assertEquals(3, uniform.get(1).size());
        assertEquals(7.0, uniform.get(1).get(0), 1e-9);
        assertEquals(7.0, uniform.get(1).get(2), 1e-9);
    }

    @Test
    @DisplayName("行对象里 profile_data 为 PGobject 时整条链路可用")
    void rowWithPgobjectIsNonEmpty() throws Exception {
        List<Map<String, Object>> rows = List.of(row(pgJsonb(PROFILE_DATA_JSON)));

        int total = 0;
        for (Map<String, Object> r : rows) {
            total += VisualizationService
                    .extractProfileSamples(service.readProfileData(r.get("profile_data")))
                    .size();
        }

        assertEquals(3, total, "这条链路是 /viz/section 的唯一数据来源，不能为空");
    }
}
