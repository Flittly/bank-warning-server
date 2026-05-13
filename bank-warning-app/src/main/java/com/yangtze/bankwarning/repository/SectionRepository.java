package com.yangtze.bankwarning.repository;

import com.yangtze.bankwarning.dto.SectionPayload;
import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SectionRepository extends AbstractJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(SectionRepository.class);

    public SectionRepository(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    public boolean existsBySectionId(String sectionId) {
        return exists("SELECT 1 FROM cross_sections WHERE section_id = :sectionId AND deleted_at IS NULL", params("sectionId", sectionId));
    }

    public Map<String, Object> insert(String taskId, Integer basicParamId, Map<String, Object> merged, SectionPayload payload) {
        log.info("[section-insert] inserting section, taskId={}, sectionId={}, bankId={}, basicParamId={}",
                taskId, payload.sectionId(), payload.bankId(), basicParamId);
        String geometryJson = writeJson(payload.geometry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("taskId", taskId);
        args.put("sectionId", payload.sectionId());
        args.put("sectionName", payload.sectionName());
        args.put("bankId", payload.bankId());
        args.put("regionCode", payload.regionCode());
        args.put("segmentIndex", merged.get("segment_index"));
        args.put("geometryJson", geometryJson);
        args.put("sectionGeometry", writeJson(payload.sectionGeometry()));
        args.put("verticalFootPoint", writeJson(merged.get("vertical_foot_point")));
        args.put("distance", merged.get("distance"));
        args.put("basicParamId", basicParamId);
        args.put("paramName", merged.get("param_name"));
        args.put("segment", merged.get("segment"));
        args.put("currentTimepoint", merged.get("current_timepoint"));
        args.put("setName", merged.get("set_name"));
        args.put("waterQs", merged.get("water_qs"));
        args.put("tidalLevel", merged.get("tidal_level"));
        args.put("benchId", merged.get("bench_id"));
        args.put("refId", merged.get("ref_id"));
        args.put("hs", merged.get("hs"));
        args.put("hc", merged.get("hc"));
        args.put("protectionLevel", merged.get("protection_level"));
        args.put("controlLevel", merged.get("control_level"));
        args.put("comparisonTimepoint", merged.get("comparison_timepoint"));
        args.put("riskThresholds", writeJson(merged.get("risk_thresholds")));
        args.put("weights", writeJson(merged.get("weights")));
        args.put("otherParams", writeJson(merged.get("other_params")));
        update(
                """
                INSERT INTO cross_sections (
                    task_id, section_id, section_name, bank_id, region_code, segment_index,
                    start_point, end_point, geom, section_geometry, vertical_foot_point, distance, basic_param_id,
                    param_name, segment, current_timepoint, set_name, water_qs, tidal_level,
                    bench_id, ref_id, hs, hc, protection_level, control_level,
                    comparison_timepoint, risk_thresholds, weights, other_params
                ) VALUES (
                    :taskId, :sectionId, :sectionName, :bankId, :regionCode, :segmentIndex,
                    ST_StartPoint(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326)),
                    ST_EndPoint(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326)),
                    ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326), CAST(:sectionGeometry AS jsonb), CAST(:verticalFootPoint AS jsonb), :distance, :basicParamId,
                    :paramName, :segment, :currentTimepoint, :setName, :waterQs, :tidalLevel,
                    :benchId, :refId, :hs, :hc, :protectionLevel, :controlLevel,
                    :comparisonTimepoint, CAST(:riskThresholds AS jsonb), CAST(:weights AS jsonb), CAST(:otherParams AS jsonb)
                )
                """,
                args);
        log.info("[section-insert] insert success, taskId={}, sectionId={}", taskId, payload.sectionId());
        return getBySectionId(payload.sectionId());
    }

    public List<Map<String, Object>> list(String taskId, String bankId) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT cs.*, t.task_id AS task_code, t.task_name, ST_AsGeoJSON(cs.geom)::jsonb AS geometry
                FROM cross_sections cs
                JOIN tasks t ON cs.task_id = t.task_id
                WHERE cs.deleted_at IS NULL AND t.deleted_at IS NULL
                """);
        Map<String, Object> args = new LinkedHashMap<>();
        if (taskId != null) {
            sql.append(" AND cs.task_id = :taskId");
            args.put("taskId", taskId);
        }
        if (bankId != null) {
            sql.append(" AND cs.bank_id = :bankId");
            args.put("bankId", bankId);
        }
        sql.append(" ORDER BY cs.id");
        return queryList(sql.toString(), args);
    }

    public Map<String, Object> getBySectionId(String sectionId) {
        return queryOne(
                """
                SELECT cs.*, t.task_id AS task_code, t.task_name, ST_AsGeoJSON(cs.geom)::jsonb AS geometry
                FROM cross_sections cs
                JOIN tasks t ON cs.task_id = t.task_id
                WHERE cs.section_id = :sectionId AND cs.deleted_at IS NULL AND t.deleted_at IS NULL
                """,
                params("sectionId", sectionId));
    }

    public void update(String sectionId, SectionPayload payload) {
        String geometryJson = payload.geometry() == null ? null : writeJson(payload.geometry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sectionName", payload.sectionName());
        args.put("bankId", payload.bankId());
        args.put("regionCode", payload.regionCode());
        args.put("segmentIndex", payload.segmentIndex());
        args.put("geometryJson", geometryJson);
        args.put("sectionGeometry", writeJson(payload.sectionGeometry()));
        args.put("verticalFootPoint", writeJson(payload.verticalFootPoint()));
        args.put("distance", payload.distance());
        args.put("basicParamId", payload.basicParamId());
        args.put("paramName", payload.paramName());
        args.put("segment", payload.segment());
        args.put("currentTimepoint", payload.currentTimepoint());
        args.put("setName", payload.setName());
        args.put("waterQs", payload.waterQs());
        args.put("tidalLevel", payload.tidalLevel());
        args.put("benchId", payload.benchId());
        args.put("refId", payload.refId());
        args.put("hs", payload.hs());
        args.put("hc", payload.hc());
        args.put("protectionLevel", payload.protectionLevel());
        args.put("controlLevel", payload.controlLevel());
        args.put("comparisonTimepoint", payload.comparisonTimepoint());
        args.put("riskThresholds", writeJson(payload.riskThresholds()));
        args.put("weights", writeJson(payload.weights()));
        args.put("otherParams", writeJson(payload.otherParams()));
        args.put("sectionId", sectionId);
        update(
                """
                UPDATE cross_sections
                SET section_name = COALESCE(:sectionName, section_name),
                    bank_id = COALESCE(:bankId, bank_id),
                    region_code = COALESCE(:regionCode, region_code),
                    segment_index = COALESCE(:segmentIndex, segment_index),
                    start_point = COALESCE(ST_StartPoint(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326)), start_point),
                    end_point = COALESCE(ST_EndPoint(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326)), end_point),
                    geom = COALESCE(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326), geom),
                    section_geometry = COALESCE(CAST(:sectionGeometry AS jsonb), section_geometry),
                    vertical_foot_point = COALESCE(CAST(:verticalFootPoint AS jsonb), vertical_foot_point),
                    distance = COALESCE(:distance, distance),
                    basic_param_id = COALESCE(:basicParamId, basic_param_id),
                    param_name = COALESCE(:paramName, param_name),
                    segment = COALESCE(:segment, segment),
                    current_timepoint = COALESCE(:currentTimepoint, current_timepoint),
                    set_name = COALESCE(:setName, set_name),
                    water_qs = COALESCE(:waterQs, water_qs),
                    tidal_level = COALESCE(:tidalLevel, tidal_level),
                    bench_id = COALESCE(:benchId, bench_id),
                    ref_id = COALESCE(:refId, ref_id),
                    hs = COALESCE(:hs, hs),
                    hc = COALESCE(:hc, hc),
                    protection_level = COALESCE(:protectionLevel, protection_level),
                    control_level = COALESCE(:controlLevel, control_level),
                    comparison_timepoint = COALESCE(:comparisonTimepoint, comparison_timepoint),
                    risk_thresholds = COALESCE(CAST(:riskThresholds AS jsonb), risk_thresholds),
                    weights = COALESCE(CAST(:weights AS jsonb), weights),
                    other_params = COALESCE(CAST(:otherParams AS jsonb), other_params)
                WHERE section_id = :sectionId
                """,
                args);
    }

    public int deleteBySectionId(String sectionId) {
        return update("UPDATE cross_sections SET deleted_at = CURRENT_TIMESTAMP WHERE section_id = :sectionId AND deleted_at IS NULL", params("sectionId", sectionId));
    }

    public int countByTaskId(String taskId) {
        return queryInt("SELECT COUNT(*) FROM cross_sections WHERE task_id = :taskId AND deleted_at IS NULL", params("taskId", taskId));
    }

    public int deleteByTaskId(String taskId) {
        return update("UPDATE cross_sections SET deleted_at = CURRENT_TIMESTAMP WHERE task_id = :taskId AND deleted_at IS NULL", params("taskId", taskId));
    }
}
