package com.yangtze.bankwarning.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SectionProfileService {

    private static final Logger log = LoggerFactory.getLogger(SectionProfileService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final BusinessStoreService businessStoreService;
    private final ModelGatewayService modelGatewayService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true);

    public SectionProfileService(
            BusinessStoreService businessStoreService,
            ModelGatewayService modelGatewayService) {
        this.businessStoreService = businessStoreService;
        this.modelGatewayService = modelGatewayService;
    }

    public void saveForSection(String taskId, Map<String, Object> section) {
        String sectionId = String.valueOf(section.get("section_id"));
        Map<String, Object> payload = buildSectionViewPayload(section);
        Map<String, Object> sectionViewResult = modelGatewayService.runLegacyModelAndWaitForCase(
                "/v0/re/section-view",
                payload,
                null);
        String caseId = String.valueOf(sectionViewResult.get("caseId"));
        Map<String, Object> result = castMap(sectionViewResult.get("result"));
        String rawJsonName = Objects.toString(result.get("raw-json"), "").trim();
        // RESPONSING 在模型跑完前先落盘一份占位响应（raw-json = "NONE"）。模型失败时
        // 这份占位不会被覆盖，于是 "NONE" 会以「文件名」的身份一路走到 fetchModelCaseFile，
        // 换回来的是一个取不到文件的二次异常，真正的失败原因（DEM 无数据等）就此丢失。
        // 在这里就把它当失败处理，并把模型侧写的 error-code / error-message 带出去。
        if (rawJsonName.isEmpty() || isPlaceholder(rawJsonName)) {
            throw new IllegalStateException(buildSectionViewFailureMessage(sectionId, result));
        }
        String rawJsonText = modelGatewayService.fetchModelCaseFile(caseId, rawJsonName);
        Map<String, Object> sectionJson = readJsonMap(rawJsonText);
        Map<String, Object> profileData = buildProfileData(sectionJson, result.get("interval"));

        businessStoreService.saveSectionProfile(
                taskId,
                sectionId,
                String.valueOf(section.get("section_name")),
                String.valueOf(section.get("region_code")),
                String.valueOf(section.get("bank_id")),
                Objects.toString(section.get("bench_id"), null),
                caseId,
                toNumber(result.get("interval")),
                toNullableInteger(sectionJson.get("deepest_index")),
                toNullableInteger(sectionJson.get("slope_foot_index")),
                countPoints(sectionJson.get("points")),
                profileData,
                castMap(section.get("geometry")));
        log.info("[section-profile] saved, taskId={}, sectionId={}, caseId={}", taskId, sectionId, caseId);
    }

    /** 模型未真正产出结果时 RESPONSING 写下的占位值：它是标记，不是文件名。 */
    private static boolean isPlaceholder(String value) {
        return "NONE".equalsIgnoreCase(value) || "TEMPLATE".equalsIgnoreCase(value);
    }

    /**
     * 失败消息刻意把 error_code 放在前面（形如 {@code error_code=DEM_COVERAGE_INSUFFICIENT}）：
     * TaskExecutionService.classifyError 是按码匹配的，先匹配到码就不会退化成 UNKNOWN_ERROR，
     * 前端也才能给出「DEM 无数据」这种可操作的提示，而不是一句「未知错误」。
     */
    private static String buildSectionViewFailureMessage(String sectionId, Map<String, Object> result) {
        String code = Objects.toString(result.get("error-code"), "").trim();
        if (code.isEmpty()) {
            code = "SECTION_VIEW_FAILED";
        }
        String detail = Objects.toString(result.get("error-message"), "").trim();
        return String.format(
                "Section View failed, section_id=%s, error_code=%s%s",
                sectionId,
                code,
                detail.isEmpty() ? "" : ", error_message=" + detail);
    }

    private Map<String, Object> buildSectionViewPayload(Map<String, Object> section) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dem-id", section.get("bench_id"));
        payload.put("section-geometry", section.get("section_geometry"));
        payload.put("segment", section.get("bench_segment"));
        payload.put("current-timepoint", section.get("current_timepoint"));
        return payload;
    }

    private Map<String, Object> buildProfileData(Map<String, Object> sectionJson, Object intervalValue) {
        double interval = toDouble(intervalValue);
        List<Map<String, Object>> profile = new ArrayList<>();
        Object pointsValue = sectionJson.get("points");
        if (pointsValue instanceof List<?> points) {
            for (int index = 0; index < points.size(); index++) {
                Object pointValue = points.get(index);
                if (!(pointValue instanceof List<?> point) || point.size() < 3) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", index);
                row.put("distance", interval * index);
                row.put("x", toDouble(point.get(0)));
                row.put("y", toDouble(point.get(1)));
                row.put("elevation", toDouble(point.get(2)));
                profile.add(row);
            }
        }

        Map<String, Object> profileData = new LinkedHashMap<>();
        profileData.put("profile", profile);
        profileData.put("interval", toNumber(intervalValue));
        profileData.put("deepest_index", toNullableInteger(sectionJson.get("deepest_index")));
        profileData.put("slope_foot_index", toNullableInteger(sectionJson.get("slope_foot_index")));
        profileData.put("points_v", sectionJson.get("points_v"));
        profileData.put("Sa_v", sectionJson.get("Sa_v"));
        return profileData;
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse section profile JSON", exception);
        }
    }

    private Integer countPoints(Object pointsValue) {
        if (pointsValue instanceof List<?> points) {
            return points.size();
        }
        return 0;
    }

    private Integer toNullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Number toNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return null;
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }
}
