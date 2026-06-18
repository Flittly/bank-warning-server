package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TiffBoundsService {

    private static final Logger log = LoggerFactory.getLogger(TiffBoundsService.class);
    private final JdbcTemplate jdbcTemplate;

    public TiffBoundsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> listTiffBoundsAsGeoJson() {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        String sql = """
                SELECT tiff_key, region_code, year, timepoint,
                       ST_AsGeoJSON(geom) AS geometry
                FROM tiff_bounds
                WHERE geom IS NOT NULL AND (user_id = ? OR ? IS NULL)
                ORDER BY tiff_key
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId, userId);

        List<Map<String, Object>> features = rows.stream().map(row -> {
            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("type", "Feature");

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("tiff_key", row.get("tiff_key"));
            properties.put("region_code", row.get("region_code"));
            properties.put("year", row.get("year"));
            properties.put("timepoint", row.get("timepoint"));
            feature.put("properties", properties);

            String geoJson = (String) row.get("geometry");
            try {
                feature.put("geometry", new com.fasterxml.jackson.databind.ObjectMapper().readValue(geoJson, Map.class));
            } catch (Exception e) {
                log.warn("[tiff-bounds] failed to parse GeoJSON for tiff_key={}: {}", row.get("tiff_key"), e.getMessage());
                feature.put("geometry", null);
            }

            return feature;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "FeatureCollection");
        result.put("features", features);
        return result;
    }
}
