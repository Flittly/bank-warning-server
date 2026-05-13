package com.yangtze.bankwarning.repository;

import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SectionProfileRepository extends AbstractJdbcRepository {

    public SectionProfileRepository(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    public void save(
            String taskId,
            String sectionId,
            String sectionName,
            String regionCode,
            String bankId,
            String demId,
            String sourceCaseId,
            Number interval,
            Integer deepestIndex,
            Integer slopeFootIndex,
            Integer pointCount,
            Map<String, Object> profileData,
            Map<String, Object> geometry) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("taskId", taskId);
        args.put("sectionId", sectionId);
        args.put("sectionName", sectionName);
        args.put("regionCode", regionCode);
        args.put("bankId", bankId);
        args.put("demId", demId);
        args.put("sourceCaseId", sourceCaseId);
        args.put("interval", interval);
        args.put("deepestIndex", deepestIndex);
        args.put("slopeFootIndex", slopeFootIndex);
        args.put("pointCount", pointCount);
        args.put("profileData", writeJson(profileData));
        args.put("geometry", writeJson(geometry));
        update(
                """
                INSERT INTO section_profiles (
                    task_id, section_id, section_name, region_code, bank_id, dem_id,
                    source_case_id, interval, deepest_index, slope_foot_index, point_count,
                    profile_data, geom
                ) VALUES (
                    :taskId, :sectionId, :sectionName, :regionCode, :bankId, :demId,
                    :sourceCaseId, :interval, :deepestIndex, :slopeFootIndex, :pointCount,
                    CAST(:profileData AS jsonb), ST_SetSRID(ST_GeomFromGeoJSON(:geometry), 4326)
                )
                ON CONFLICT (task_id, section_id) DO UPDATE SET
                    section_name = EXCLUDED.section_name,
                    region_code = EXCLUDED.region_code,
                    bank_id = EXCLUDED.bank_id,
                    dem_id = EXCLUDED.dem_id,
                    source_case_id = EXCLUDED.source_case_id,
                    interval = EXCLUDED.interval,
                    deepest_index = EXCLUDED.deepest_index,
                    slope_foot_index = EXCLUDED.slope_foot_index,
                    point_count = EXCLUDED.point_count,
                    profile_data = EXCLUDED.profile_data,
                    geom = EXCLUDED.geom,
                    updated_at = CURRENT_TIMESTAMP
                """,
                args);
    }

    public List<Map<String, Object>> listByTaskId(String taskId) {
        return queryList(
                """
                SELECT sp.*, ST_AsGeoJSON(sp.geom)::jsonb AS geometry
                FROM section_profiles sp
                WHERE sp.task_id = :taskId AND sp.deleted_at IS NULL
                ORDER BY sp.id
                """,
                params("taskId", taskId));
    }

    public Map<String, Object> getLatestBySectionId(String sectionId) {
        return queryOne(
                """
                SELECT sp.*, ST_AsGeoJSON(sp.geom)::jsonb AS geometry
                FROM section_profiles sp
                WHERE sp.section_id = :sectionId AND sp.deleted_at IS NULL
                ORDER BY sp.id DESC
                LIMIT 1
                """,
                params("sectionId", sectionId));
    }

    public int deleteByTaskId(String taskId) {
        return update("UPDATE section_profiles SET deleted_at = CURRENT_TIMESTAMP WHERE task_id = :taskId AND deleted_at IS NULL", params("taskId", taskId));
    }
}
