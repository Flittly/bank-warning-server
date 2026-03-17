package com.yangtze.bankwarning.repository;

import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class RiskResultRepository extends AbstractJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(RiskResultRepository.class);

    public RiskResultRepository(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    public List<Map<String, Object>> list(String taskId, String bankId, String regionCode) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT brr.*, ST_AsGeoJSON(brr.geom)::jsonb AS geometry
                FROM bank_risk_results brr
                """);
        Map<String, Object> args = new LinkedHashMap<>();
        appendEqualsCondition(sql, args, "brr.task_id", "taskId", taskId);
        appendEqualsCondition(sql, args, "brr.bank_id", "bankId", bankId);
        appendEqualsCondition(sql, args, "brr.region_code", "regionCode", regionCode);
        sql.append(" ORDER BY brr.id");
        return queryList(sql.toString(), args);
    }

    public Map<String, Object> getLatestBySectionId(String sectionId) {
        return queryOne(
                """
                SELECT brr.*, ST_AsGeoJSON(brr.geom)::jsonb AS geometry
                FROM bank_risk_results brr
                WHERE brr.section_id = :sectionId
                ORDER BY brr.id DESC
                LIMIT 1
                """,
                params("sectionId", sectionId));
    }

    public void save(String taskId, String sectionId, String sectionName, String regionCode, String bankId, Integer riskLevel, Map<String, Object> indicators, Map<String, Object> geometry) {
        log.info("[risk-result-save] inserting result, taskId={}, sectionId={}, bankId={}, regionCode={}, riskLevel={}, geometryKeys={}, indicatorKeys={}",
                taskId,
                sectionId,
                bankId,
                regionCode,
                riskLevel,
                geometry == null ? "null" : geometry.keySet(),
                indicators == null ? "null" : indicators.keySet());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("taskId", taskId);
        args.put("sectionId", sectionId);
        args.put("sectionName", sectionName);
        args.put("regionCode", regionCode);
        args.put("bankId", bankId);
        args.put("riskLevel", riskLevel);
        args.put("indicators", writeJson(indicators));
        args.put("geometry", writeJson(geometry));
        update(
                """
                INSERT INTO bank_risk_results (
                    task_id, section_id, section_name, region_code, bank_id,
                    risk_level, indicators, geom
                ) VALUES (
                    :taskId, :sectionId, :sectionName, :regionCode, :bankId,
                    :riskLevel, CAST(:indicators AS jsonb), ST_SetSRID(ST_GeomFromGeoJSON(:geometry), 4326)
                )
                """,
                args);
        log.info("[risk-result-save] insert success, taskId={}, sectionId={}", taskId, sectionId);
    }

    public int countByTaskId(String taskId) {
        return queryInt("SELECT COUNT(*) FROM bank_risk_results WHERE task_id = :taskId", params("taskId", taskId));
    }

    public int deleteByTaskId(String taskId) {
        return update("DELETE FROM bank_risk_results WHERE task_id = :taskId", params("taskId", taskId));
    }
}
