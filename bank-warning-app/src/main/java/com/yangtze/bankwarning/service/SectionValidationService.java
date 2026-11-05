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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Section 验证服务
 * 验证 section 是否与长江相交，是否在 tiff 范围内
 *
 * <h3>规则开关（2026-10-20）</h3>
 * 资源树里「规则」节点上的开关会把**被停用**的规则 id 随校验请求带下来（{@code disabled_rules}）。
 * 这里只做一件事：判定前查一次集合，命中就跳过该条并记入 {@code skipped_rules}。
 * <ul>
 *   <li>不传 / 传空 = 全部启用，行为与改造前逐字节一致；</li>
 *   <li>只有 {@link #SWITCHABLE_RULES} 里的规则能被跳过 —— 前置条件（必须指定基准 DEM）
 *       传进来也会被忽略并打 warn，因为关掉它不是"降级"而是"算不出来"；</li>
 *   <li>跳过不等于放行错误数据：判定被跳过后，问题会在模型执行阶段以更具体的错误暴露
 *       （如 {@code DEM_COVERAGE_INSUFFICIENT}）。</li>
 * </ul>
 */
@Service
public class SectionValidationService extends AbstractJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(SectionValidationService.class);

    /** 规则 id —— 与前端 `src/rules/catalog.ts` 逐字一致，改名字必须两边同时改 */
    public static final String RULE_RIVER_INTERSECT = "R-SPACE-01";
    public static final String RULE_NOT_INSIDE_RIVER = "R-SPACE-02";
    public static final String RULE_BENCH_REQUIRED = "R-SPACE-03";
    public static final String RULE_WITHIN_TIFF = "R-SPACE-04";

    /** 允许被前端停用的规则。R-SPACE-03 是前置条件，不在此列。 */
    public static final Set<String> SWITCHABLE_RULES =
            Set.of(RULE_RIVER_INTERSECT, RULE_NOT_INSIDE_RIVER, RULE_WITHIN_TIFF);

    private static final Map<String, String> RULE_LABELS = Map.of(
            RULE_RIVER_INTERSECT, "与长江河面相交",
            RULE_NOT_INSIDE_RIVER, "不得完全落在江面内",
            RULE_BENCH_REQUIRED, "必须指定基准 DEM",
            RULE_WITHIN_TIFF, "须位于 DEM 覆盖范围内"
    );

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
     * 验证 section 的完整性（全部规则启用）。
     *
     * @param sectionId       section 的业务 ID
     * @param sectionGeometry section 的 GeoJSON 几何
     * @param benchId         tiff 文件的 bench_id（用于获取 tiff 边界）
     * @return 验证结果
     */
    public ValidationResponse validateSection(String sectionId, Map<String, Object> sectionGeometry, String benchId) {
        return validateSection(sectionId, sectionGeometry, benchId, Set.of());
    }

    /**
     * 验证 section 的完整性，按 {@code disabledRules} 跳过被停用的规则。
     *
     * @param disabledRules 被停用的规则 id（null / 空 = 全部启用）。只认 {@link #SWITCHABLE_RULES}。
     */
    public ValidationResponse validateSection(
            String sectionId,
            Map<String, Object> sectionGeometry,
            String benchId,
            Set<String> disabledRules) {

        Set<String> disabled = normalizeDisabled(disabledRules);
        Set<String> skipped = new LinkedHashSet<>();
        log.info("[section-validation] 开始验证 sectionId={}, 停用规则={}", sectionId, disabled);

        // 1. 验证与长江相交
        if (disabled.contains(RULE_RIVER_INTERSECT)) {
            skipped.add(RULE_RIVER_INTERSECT);
            log.info("[section-validation] 规则已停用，跳过 {} sectionId={}", RULE_RIVER_INTERSECT, sectionId);
        } else {
            boolean intersectsRiver = validateIntersectsRiver(sectionGeometry);
            if (!intersectsRiver) {
                log.warn("[section-validation] section 未与长江相交 sectionId={}", sectionId);
                updateSectionValidation(sectionId, false, "invalid_no_river", "Section 未与长江面相交");
                return new ValidationResponse(false, "invalid_no_river", "Section 未与长江面相交", skipped);
            }
        }

        // 1.1 验证 section 不能完全在河流面内部
        if (disabled.contains(RULE_NOT_INSIDE_RIVER)) {
            skipped.add(RULE_NOT_INSIDE_RIVER);
            log.info("[section-validation] 规则已停用，跳过 {} sectionId={}", RULE_NOT_INSIDE_RIVER, sectionId);
        } else {
            boolean fullyInsideRiver = validateNotFullyInsideRiver(sectionGeometry);
            if (fullyInsideRiver) {
                log.warn("[section-validation] section 完全在河流面内部 sectionId={}", sectionId);
                updateSectionValidation(sectionId, false, "invalid_inside_river", "Section 完全在河流面内部，不合法");
                return new ValidationResponse(false, "invalid_inside_river", "Section 完全在河流面内部，不合法", skipped);
            }
        }

        // 2. 获取 tiff 边界（R-SPACE-03 是前置条件，不参与开关：
        //    没有基准 DEM 就没有可采样的高程面，关掉不是"降级"而是"算不出来"）
        if (benchId == null || benchId.isEmpty()) {
            log.warn("[section-validation] bench_id 为空 sectionId={}", sectionId);
            updateSectionValidation(sectionId, false, "invalid_no_tiff", "未指定 bench_id，无法验证 tiff 范围");
            return new ValidationResponse(false, "invalid_no_tiff", "未指定 bench_id，无法验证 tiff 范围", skipped);
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
            return new ValidationResponse(null, "pending_tiff_bounds", "Tiff 边界信息待提取", skipped);
        }

        // 3. 验证在 tiff 范围内
        if (disabled.contains(RULE_WITHIN_TIFF)) {
            skipped.add(RULE_WITHIN_TIFF);
            log.info("[section-validation] 规则已停用，跳过 {} sectionId={}", RULE_WITHIN_TIFF, sectionId);
        } else {
            boolean withinTiffBounds = validateWithinTiffBounds(sectionGeometry, tiffKey);
            if (!withinTiffBounds) {
                log.warn("[section-validation] section 超出 tiff 范围 sectionId={}, tiffKey={}", sectionId, tiffKey);
                updateSectionValidation(sectionId, false, "invalid_out_of_tiff", "Section 超出 tiff 数据范围");
                return new ValidationResponse(false, "invalid_out_of_tiff", "Section 超出 tiff 数据范围", skipped);
            }
        }

        // 4. 验证通过
        String message = skipped.isEmpty()
                ? "Section 验证通过"
                : "Section 验证通过（已跳过：" + skippedLabels(skipped) + "）";
        log.info("[section-validation] section 验证通过 sectionId={}, 跳过={}", sectionId, skipped);
        updateSectionValidation(sectionId, true, "valid", message);
        return new ValidationResponse(true, "valid", message, skipped);
    }

    /**
     * 只保留真正可被停用的规则；未知 id（前端版本领先 / 拼错）与不可关规则的 id 一律忽略并打 warn，
     * 不抛 400 —— 否则一条过期的规则 id 会让整批断面校验全部失败。
     */
    private Set<String> normalizeDisabled(Set<String> disabledRules) {
        if (disabledRules == null || disabledRules.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : disabledRules) {
            if (raw == null || raw.isBlank()) continue;
            String id = raw.trim();
            if (SWITCHABLE_RULES.contains(id)) {
                out.add(id);
            } else if (RULE_LABELS.containsKey(id)) {
                log.warn("[section-validation] 规则 {} 是前置条件/固定规则，忽略停用请求", id);
            } else {
                log.warn("[section-validation] 未知规则 id，已忽略：{}", id);
            }
        }
        return out;
    }

    private String skippedLabels(Set<String> skipped) {
        return skipped.stream()
                .map(id -> RULE_LABELS.getOrDefault(id, id))
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
    }

    /** 供 GET /v0/bank/validation-rules 使用：当前实现支持的全部规则及其可关性 */
    public List<Map<String, Object>> describeRules() {
        return List.of(
                ruleInfo(RULE_RIVER_INTERSECT),
                ruleInfo(RULE_NOT_INSIDE_RIVER),
                ruleInfo(RULE_BENCH_REQUIRED),
                ruleInfo(RULE_WITHIN_TIFF)
        );
    }

    private Map<String, Object> ruleInfo(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", RULE_LABELS.getOrDefault(id, id));
        m.put("switchable", SWITCHABLE_RULES.contains(id));
        return m;
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
     * 验证结果。
     *
     * @param isValid      通过 / 不通过 / null=待定（tiff 边界还没提取出来）
     * @param status       业务状态码（valid / invalid_xxx / pending_tiff_bounds）
     * @param message      人话，已跳过规则时会追加"（已跳过：…）"
     * @param skippedRules 本次被停用而跳过的规则 id，供前端展示与排障
     */
    public record ValidationResponse(
            Boolean isValid,
            String status,
            String message,
            Set<String> skippedRules
    ) {
        /** 兼容旧调用点（不带规则集时等价于"全部启用、无跳过"） */
        public ValidationResponse(Boolean isValid, String status, String message) {
            this(isValid, status, message, Set.of());
        }
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
