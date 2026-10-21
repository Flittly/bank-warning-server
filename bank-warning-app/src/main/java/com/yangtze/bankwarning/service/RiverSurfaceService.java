package com.yangtze.bankwarning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长江河面（`river_yangtze`）范围图层的出图数据源。
 *
 * <p><b>为什么要有这个类：</b>断面校验里「与长江河面相交」这条规则读的是
 * `river_yangtze.geom`（见 {@link SectionValidationService#validateIntersectsRiver}），
 * 但前端从来没有拿到过这份几何 —— 用户只能在报错文案里看到「未与长江面相交」，
 * 却不知道长江面到底在哪、该把断面往哪边挪。这里把**同一张表的同一份几何**吐给前端，
 * 做到「地图上看到的边界，就是判定用的边界」。
 *
 * <p><b>坐标系：</b>库里是 EPSG:3857，前端 Mapbox 只认 EPSG:4326，转换放在 SQL 里做
 * （{@code ST_Transform}），不把 3857 的米制坐标漏出去 —— 前端拿到的是经纬度。
 *
 * <p><b>为什么默认不简化：</b>该表长江全段 41,354 个顶点、约 1.19 MB。一开始按 10 m 容差
 * 简化（降到 8.8k 点 / 255 KB），但实测**会改变判定结果** —— 抽样 2000 条断面，
 * 10 m 容差下 2 条断面的"是否相交"结论与原几何相反，2 m 容差下仍有 1 条。
 * 这个图层的全部意义就是"地图上看到的边界 = 判定用的边界"，一旦有位图级别的错位，
 * 用户会认为系统判错了。所以默认返回**原始几何**（只做 3857→4326 投影和 11 cm 级取整），
 * `tol` 参数留着当逃生口（网络受限时可调大）。
 */
@Service
public class RiverSurfaceService {

    private static final Logger log = LoggerFactory.getLogger(RiverSurfaceService.class);

    /** 简化容差（米，3857 平面）。0 = 不简化（默认）。 */
    private static final double MIN_TOL_M = 0.0;
    private static final double MAX_TOL_M = 200.0;
    private static final double DEFAULT_TOL_M = 0.0;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RiverSurfaceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 把入参容差夹到合法区间；缺省/负数一律按 0（不简化）处理。 */
    public static double clampTolerance(Double tolM) {
        if (tolM == null || !Double.isFinite(tolM) || tolM <= 0) return DEFAULT_TOL_M;
        return Math.min(MAX_TOL_M, tolM);
    }

    /**
     * 长江河面 → GeoJSON FeatureCollection（EPSG:4326）+ 元信息。
     *
     * <p>返回 {@code { data: FeatureCollection, meta: {...} }}。全库只有 1 个要素
     * （长江主槽，37 个环 —— 江心洲等内部空洞以 Polygon 洞的形式保留，
     * 所以填充不会把江心洲"填平"，与 {@code ST_Intersects} 的判定范围一致）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadGeoJson(double tolM) {
        // tol=0 时走 `THEN geom` 分支，不依赖 ST_SimplifyPreserveTopology(geom, 0) 的行为；
        // 简化只做一次（CTE），避免在 SELECT 里算两遍。
        String sql = """
                WITH g AS (
                    SELECT id,
                           name,
                           "level" AS river_level,
                           if_important,
                           CASE WHEN ? <= 0 THEN geom
                                ELSE ST_SimplifyPreserveTopology(geom, ?)
                           END AS gg
                    FROM river_yangtze
                    WHERE geom IS NOT NULL
                )
                SELECT id,
                       name,
                       river_level,
                       if_important,
                       ST_AsGeoJSON(ST_Force2D(ST_Transform(gg, 4326)), 6) AS geometry,
                       ST_NPoints(gg) AS points
                FROM g
                ORDER BY id
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tolM, tolM);

        long totalPoints = 0;
        List<Map<String, Object>> features = rows.stream().map(row -> {
            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("type", "Feature");

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("river_id", row.get("id"));
            properties.put("name", row.get("name"));
            properties.put("level", row.get("river_level"));
            properties.put("if_important", row.get("if_important"));
            feature.put("properties", properties);

            String geoJson = (String) row.get("geometry");
            try {
                feature.put("geometry", objectMapper.readValue(geoJson, Map.class));
            } catch (Exception e) {
                log.warn("[river-surface] GeoJSON 解析失败 river_id={}: {}", row.get("id"), e.getMessage());
                feature.put("geometry", null);
            }
            return feature;
        }).toList();

        for (Map<String, Object> row : rows) {
            if (row.get("points") instanceof Number n) totalPoints += n.longValue();
        }

        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        collection.put("features", features);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "river_yangtze");
        meta.put("srid", 4326);
        meta.put("tol_m", tolM);
        meta.put("features", features.size());
        meta.put("points", totalPoints);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", collection);
        payload.put("meta", meta);
        return payload;
    }
}
