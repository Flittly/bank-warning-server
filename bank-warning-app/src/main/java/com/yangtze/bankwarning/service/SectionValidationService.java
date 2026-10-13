package com.yangtze.bankwarning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Section 验证服务
 * 验证 section 是否与长江相交，是否在 tiff 范围内
 */
@Service
public class SectionValidationService extends AbstractJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(SectionValidationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.model-service.base-url:http://localhost:8088}")
    private String modelServiceBaseUrl;

    // 用于防止重复提取同一 tiff 边界
    private final java.util.Set<String> extractingTiffs = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public SectionValidationService(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    /**
     * 验证 section 的完整性
     *
     * @param sectionId       section 的业务 ID
     * @param sectionGeometry section 的 GeoJSON 几何
     * @param benchId         tiff 文件的 bench_id（用于获取 tiff 边界）
     * @return 验证结果
     */
    public ValidationResponse validateSection(String sectionId, Map<String, Object> sectionGeometry, String benchId) {
        log.info("[section-validation] 开始验证 sectionId={}", sectionId);

        // 1. 验证与长江相交
        boolean intersectsRiver = validateIntersectsRiver(sectionGeometry);
        if (!intersectsRiver) {
            log.warn("[section-validation] section 未与长江相交 sectionId={}", sectionId);
            updateSectionValidation(sectionId, false, "invalid_no_river", "Section 未与长江面相交");
            return new ValidationResponse(false, "invalid_no_river", "Section 未与长江面相交");
        }

        // 1.1 验证 section 不能完全在河流面内部
        boolean fullyInsideRiver = validateNotFullyInsideRiver(sectionGeometry);
        if (fullyInsideRiver) {
            log.warn("[section-validation] section 完全在河流面内部 sectionId={}", sectionId);
            updateSectionValidation(sectionId, false, "invalid_inside_river", "Section 完全在河流面内部，不合法");
            return new ValidationResponse(false, "invalid_inside_river", "Section 完全在河流面内部，不合法");
        }

        // 2. 获取 tiff 边界
        if (benchId == null || benchId.isEmpty()) {
            log.warn("[section-validation] bench_id 为空 sectionId={}", sectionId);
            updateSectionValidation(sectionId, false, "invalid_no_tiff", "未指定 bench_id，无法验证 tiff 范围");
            return new ValidationResponse(false, "invalid_no_tiff", "未指定 bench_id，无法验证 tiff 范围");
        }

        // 将 benchId 转换为 tiff_key (去掉开头的 tiff\\ 替换为 tiff/)
        String tiffKey = benchId.replace("\\", "/");

        TiffBounds tiffBounds = getTiffBounds(tiffKey);
        if (tiffBounds == null) {
            // 检查是否正在提取中，避免重复提取
            if (!extractingTiffs.contains(tiffKey)) {
                extractingTiffs.add(tiffKey);
                try {
                    log.info("[section-validation] tiff 边界不存在，尝试自动提取 sectionId={}, tiffKey={}", sectionId, tiffKey);
                    extractTiffBoundsFromPython(tiffKey);
                } catch (Exception e) {
                    log.error("[section-validation] 自动提取 tiff 边界失败 sectionId={}, tiffKey={}, error={}", sectionId, tiffKey, e.getMessage());
                } finally {
                    extractingTiffs.remove(tiffKey);
                }
            } else {
                log.info("[section-validation] tiff 边界正在提取中，等待 sectionId={}, tiffKey={}", sectionId, tiffKey);
                // 等待提取完成
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}
            }
            // 再次查询
            tiffBounds = getTiffBounds(tiffKey);
        }

        if (tiffBounds == null) {
            log.warn("[section-validation] tiff 边界不存在 sectionId={}, tiffKey={}", sectionId, tiffKey);
            updateSectionValidation(sectionId, null, "pending_tiff_bounds", "Tiff 边界信息待提取");
            return new ValidationResponse(null, "pending_tiff_bounds", "Tiff 边界信息待提取");
        }

        // 3. 验证在 tiff 范围内
        boolean withinTiffBounds = validateWithinTiffBounds(sectionGeometry, tiffKey);
        if (!withinTiffBounds) {
            log.warn("[section-validation] section 超出 tiff 范围 sectionId={}, tiffKey={}", sectionId, tiffKey);
            updateSectionValidation(sectionId, false, "invalid_out_of_tiff", "Section 超出 tiff 数据范围");
            return new ValidationResponse(false, "invalid_out_of_tiff", "Section 超出 tiff 数据范围");
        }

        // 4. 验证通过
        log.info("[section-validation] section 验证通过 sectionId={}", sectionId);
        updateSectionValidation(sectionId, true, "valid", "Section 验证通过");
        return new ValidationResponse(true, "valid", "Section 验证通过");
    }

    /**
     * 验证 section 是否与长江相交
     */
    public boolean validateIntersectsRiver(Map<String, Object> sectionGeometry) {
        try {
            String geometryJson = objectMapper.writeValueAsString(sectionGeometry);
            return queryInt(
                    """
                            SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END
                            FROM river_yangtze
                            WHERE ST_Intersects(
                                geom,
                                ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(:geometry), 4326), 3857)
                            )
                            """,
                    params("geometry", geometryJson)
            ) > 0;
        } catch (JsonProcessingException e) {
            log.error("[section-validation] JSON 序列化失败", e);
            return false;
        }
    }

    /**
     * 验证 section 是否完全在河流面内部（不合法）
     */
    public boolean validateNotFullyInsideRiver(Map<String, Object> sectionGeometry) {
        try {
            String geometryJson = objectMapper.writeValueAsString(sectionGeometry);
            // 返回 true 表示 section 完全在河流面内部（不合法）
            return queryInt(
                    """
                            SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END
                            FROM river_yangtze
                            WHERE ST_Within(
                                ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(:geometry), 4326), 3857),
                                geom
                            )
                            """,
                    params("geometry", geometryJson)
            ) > 0;
        } catch (JsonProcessingException e) {
            log.error("[section-validation] JSON 序列化失败", e);
            return false;
        }
    }

    /**
     * 验证 section 是否在 tiff 范围内
     */
    public boolean validateWithinTiffBounds(Map<String, Object> sectionGeometry, String tiffKey) {
        try {
            String geometryJson = objectMapper.writeValueAsString(sectionGeometry);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("geometry", geometryJson);
            args.put("tiffKey", tiffKey);
            args.put("userId", SecurityUtils.getCurrentUserIdForDataFilter());
            // tiff_bounds.geom 已经是 4326 坐标（存储时已转换）
            return queryInt(
                    """
                            SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END
                            FROM tiff_bounds
                            WHERE tiff_key = :tiffKey
                              AND (user_id = :userId OR CAST(:userId AS BIGINT) IS NULL)
                              AND ST_Within(
                                  ST_SetSRID(ST_GeomFromGeoJSON(:geometry), 4326),
                                  geom
                              )
                            """,
                    args
            ) > 0;
        } catch (JsonProcessingException e) {
            log.error("[section-validation] JSON 序列化失败", e);
            return false;
        }
    }

    /**
     * 获取 tiff 边界信息
     */
    public TiffBounds getTiffBounds(String tiffKey) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tiffKey", tiffKey);
        params.put("userId", userId);
        Map<String, Object> row = queryOne(
                """
                        SELECT tiff_key, min_x, min_y, max_x, max_y
                        FROM tiff_bounds
                        WHERE tiff_key = :tiffKey AND (user_id = :userId OR CAST(:userId AS BIGINT) IS NULL)
                        """,
                params
        );
        if (row == null) {
            return null;
        }
        return new TiffBounds(
                (String) row.get("tiff_key"),
                toDouble(row.get("min_x")),
                toDouble(row.get("min_y")),
                toDouble(row.get("max_x")),
                toDouble(row.get("max_y"))
        );
    }

    // 冗余清理 2026-10-13：已删除原 saveTiffBounds(...)（Java 侧自存的 tiff 边界 upsert 实现）。
    // 全仓库零调用；tiff_bounds 的真实写入由 Python 侧承担（register_tiff / extract_tiff_bounds /
    // util/extract_tiff_bounds.py），本方法属与 Python 重复的实现。
    // 详见 docs/后端冗余代码审计-2026-10-12.md 第 11.6 节。

    /**
     * 更新 section 的验证状态
     */
    public void updateSectionValidation(String sectionId, Boolean isValid, String validationStatus, String validationMessage) {
        update(
                """
                        UPDATE cross_sections
                        SET is_valid = :isValid,
                            validation_status = :validationStatus,
                            validation_message = :validationMessage,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE section_id = :sectionId
                          AND (user_id = :userId OR CAST(:userId AS BIGINT) IS NULL)
                        """,
                new LinkedHashMap<>() {{
                    put("isValid", isValid);
                    put("validationStatus", validationStatus);
                    put("validationMessage", validationMessage);
                    put("sectionId", sectionId);
                    put("userId", SecurityUtils.getCurrentUserIdForDataFilter());
                }}
        );
    }

    /**
     * 调用 Python 服务提取 tiff 边界
     */
    @SuppressWarnings("unchecked")
    private void extractTiffBoundsFromPython(String tiffKey) {
        String url = modelServiceBaseUrl + "/api/v1/tiff/extract-bounds";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("tiff_key", tiffKey);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        log.info("[section-validation] 调用 Python 提取 tiff 边界: {}", url);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        Map<String, Object> result = response.getBody();

        if (result != null && Boolean.TRUE.equals(result.get("success"))) {
            log.info("[section-validation] tiff 边界提取成功: {}", tiffKey);
        } else {
            log.warn("[section-validation] tiff 边界提取失败: {}", tiffKey);
        }
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    /**
     * 验证结果
     */
    public record ValidationResponse(
            Boolean isValid,
            String status,
            String message
    ) {
    }

    /**
     * Tiff 边界信息
     */
    public record TiffBounds(
            String tiffKey,
            double minX,
            double minY,
            double maxX,
            double maxY
    ) {
    }
}
