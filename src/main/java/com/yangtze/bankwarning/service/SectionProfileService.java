package com.yangtze.bankwarning.service;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SectionProfileService(
            BusinessStoreService businessStoreService,
            ModelGatewayService modelGatewayService) {
        this.businessStoreService = businessStoreService;
        this.modelGatewayService = modelGatewayService;
    }

    public void saveForSection(String taskCode, Map<String, Object> section) {
        String sectionId = String.valueOf(section.get("section_id"));
        Map<String, Object> payload = buildSectionViewPayload(section);
        Map<String, Object> sectionViewResult = modelGatewayService.runLegacyModelAndWaitForCase(
                "/v0/re/section-view",
                payload,
                null);
        String caseId = String.valueOf(sectionViewResult.get("caseId"));
        Map<String, Object> result = castMap(sectionViewResult.get("result"));
        String rawJsonName = String.valueOf(result.get("raw-json"));
        String rawJsonText = modelGatewayService.fetchModelCaseFile(caseId, rawJsonName);
        Map<String, Object> sectionJson = readJsonMap(rawJsonText);
        Map<String, Object> profileData = buildProfileData(sectionJson, result.get("interval"));

        businessStoreService.saveSectionProfile(
                taskCode,
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
        log.info("[section-profile] saved, taskCode={}, sectionId={}, caseId={}", taskCode, sectionId, caseId);
    }

    private Map<String, Object> buildSectionViewPayload(Map<String, Object> section) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dem-id", section.get("bench_id"));
        payload.put("section-geometry", section.get("section_geometry"));
        payload.put("segment", section.get("segment"));
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
