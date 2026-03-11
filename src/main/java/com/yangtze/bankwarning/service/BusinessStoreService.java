package com.yangtze.bankwarning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.dto.BankPayload;
import com.yangtze.bankwarning.dto.BasicParamPayload;
import com.yangtze.bankwarning.dto.SectionPayload;
import com.yangtze.bankwarning.dto.TaskPayload;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BusinessStoreService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BusinessStoreService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> saveBank(BankPayload payload, boolean overwrite) {
        if (overwrite && exists("SELECT 1 FROM banks WHERE bank_id = ?", payload.bankId())) {
            updateBank(payload.bankId(), payload);
            return getBank(payload.bankId());
        }
        String geometryJson = writeJson(payload.geometry());
        jdbcTemplate.update(
                """
                INSERT INTO banks (
                    bank_id, bank_name, region_code,
                    start_point, end_point, geom, bank_geometry, description
                ) VALUES (
                    ?, ?, ?,
                    ST_StartPoint(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)),
                    ST_EndPoint(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)),
                    ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                    ?::jsonb, ?
                )
                """,
                payload.bankId(),
                payload.bankName(),
                payload.regionCode(),
                geometryJson,
                geometryJson,
                geometryJson,
                writeJson(payload.bankGeometry()),
                payload.description());
        return getBank(payload.bankId());
    }

    public List<Map<String, Object>> listBanks(String regionCode) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT b.*, ST_AsGeoJSON(b.geom)::jsonb AS geometry
                FROM banks b
                """);
        List<Object> args = new ArrayList<>();
        appendEqualsCondition(sql, args, "b.region_code", regionCode);
        sql.append(" ORDER BY b.id");
        return jdbcTemplate.query(sql.toString(), mapRow(), args.toArray());
    }

    public Map<String, Object> getBank(String bankId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                """
                SELECT b.*, ST_AsGeoJSON(b.geom)::jsonb AS geometry
                FROM banks b WHERE b.bank_id = ?
                """,
                mapRow(),
                bankId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
        return rows.getFirst();
    }

    public void updateBank(String bankId, BankPayload payload) {
        require(getBank(bankId), "Bank not found: " + bankId);
        String geometryJson = writeJson(payload.geometry());
        jdbcTemplate.update(
                """
                UPDATE banks
                SET bank_name = ?,
                    region_code = ?,
                    start_point = ST_StartPoint(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)),
                    end_point = ST_EndPoint(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)),
                    geom = ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                    bank_geometry = ?::jsonb,
                    description = ?
                WHERE bank_id = ?
                """,
                payload.bankName(),
                payload.regionCode(),
                geometryJson,
                geometryJson,
                geometryJson,
                writeJson(payload.bankGeometry()),
                payload.description(),
                bankId);
    }

    public void deleteBank(String bankId) {
        if (jdbcTemplate.update("DELETE FROM banks WHERE bank_id = ?", bankId) == 0) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
    }

    public Map<String, Object> saveTask(TaskPayload payload, boolean overwrite) {
        if (overwrite && exists("SELECT 1 FROM tasks WHERE task_id = ?", payload.taskId())) {
            jdbcTemplate.update(
                    "UPDATE tasks SET task_name = ?, bank_ids = ?::jsonb, description = ? WHERE task_id = ?",
                    payload.taskName(),
                    writeJson(payload.bankIds()),
                    payload.description(),
                    payload.taskId());
            return getTask(payload.taskId());
        }
        jdbcTemplate.update(
                "INSERT INTO tasks (task_id, task_name, bank_ids, description) VALUES (?, ?, ?::jsonb, ?)",
                payload.taskId(),
                payload.taskName(),
                writeJson(payload.bankIds()),
                payload.description());
        return getTask(payload.taskId());
    }

    public List<Map<String, Object>> listTasks() {
        return jdbcTemplate.query("SELECT * FROM tasks ORDER BY id DESC", mapRow());
    }

    public Map<String, Object> getTask(String taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT * FROM tasks WHERE task_id = ?",
                mapRow(),
                taskId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return rows.getFirst();
    }

    public void updateTaskStatus(String taskId, String status, String runStartedAt, String runCompletedAt, String errorMessage) {
        require(getTask(taskId), "Task not found: " + taskId);
        jdbcTemplate.update(
                """
                UPDATE tasks
                SET status = COALESCE(?, status),
                    run_started_at = COALESCE(CAST(? AS TIMESTAMP), run_started_at),
                    run_completed_at = COALESCE(CAST(? AS TIMESTAMP), run_completed_at),
                    error_message = ?
                WHERE task_id = ?
                """,
                status,
                runStartedAt,
                runCompletedAt,
                errorMessage,
                taskId);
    }

    public void markTaskRunning(String taskId) {
        jdbcTemplate.update(
                "UPDATE tasks SET status = 'running', run_started_at = CURRENT_TIMESTAMP, run_completed_at = NULL, error_message = NULL WHERE task_id = ?",
                taskId);
    }

    public void markTaskCompleted(String taskId) {
        jdbcTemplate.update(
                "UPDATE tasks SET status = 'completed', run_completed_at = CURRENT_TIMESTAMP, error_message = NULL WHERE task_id = ?",
                taskId);
    }

    public void markTaskError(String taskId, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE tasks SET status = 'error', run_completed_at = CURRENT_TIMESTAMP, error_message = ? WHERE task_id = ?",
                errorMessage,
                taskId);
    }

    public void deleteTask(String taskId) {
        if (jdbcTemplate.update("DELETE FROM tasks WHERE task_id = ?", taskId) == 0) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
    }

    public Map<String, Object> saveBasicParam(BasicParamPayload payload, boolean overwrite) {
        if (overwrite && exists("SELECT 1 FROM basic_params WHERE param_id = ?", payload.paramId())) {
            updateBasicParam(payload.paramId(), payload);
            return getBasicParam(payload.paramId());
        }
        jdbcTemplate.update(
                """
                INSERT INTO basic_params (
                    param_id, param_name, segment, current_timepoint, set_name, water_qs, tidal_level,
                    bench_id, ref_id, hs, hc, protection_level, control_level,
                    comparison_timepoint, risk_thresholds, weights, other_params
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
                payload.paramId(),
                payload.paramName(),
                payload.segment(),
                payload.currentTimepoint(),
                payload.setName(),
                payload.waterQs(),
                payload.tidalLevel(),
                payload.benchId(),
                payload.refId(),
                payload.hs(),
                payload.hc(),
                payload.protectionLevel(),
                payload.controlLevel(),
                payload.comparisonTimepoint(),
                writeJson(payload.riskThresholds()),
                writeJson(payload.weights()),
                writeJson(payload.otherParams()));
        return getBasicParam(payload.paramId());
    }

    public List<Map<String, Object>> listBasicParams() {
        return jdbcTemplate.query("SELECT * FROM basic_params ORDER BY id DESC", mapRow());
    }

    public Map<String, Object> getBasicParam(String paramId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT * FROM basic_params WHERE param_id = ?",
                mapRow(),
                paramId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Basic parameter not found: " + paramId);
        }
        return rows.getFirst();
    }

    public void updateBasicParam(String paramId, BasicParamPayload payload) {
        require(getBasicParam(paramId), "Basic parameter not found: " + paramId);
        jdbcTemplate.update(
                """
                UPDATE basic_params
                SET param_name = COALESCE(?, param_name),
                    segment = COALESCE(?, segment),
                    current_timepoint = COALESCE(?, current_timepoint),
                    set_name = COALESCE(?, set_name),
                    water_qs = COALESCE(?, water_qs),
                    tidal_level = COALESCE(?, tidal_level),
                    bench_id = COALESCE(?, bench_id),
                    ref_id = COALESCE(?, ref_id),
                    hs = COALESCE(?, hs),
                    hc = COALESCE(?, hc),
                    protection_level = COALESCE(?, protection_level),
                    control_level = COALESCE(?, control_level),
                    comparison_timepoint = COALESCE(?, comparison_timepoint),
                    risk_thresholds = COALESCE(?::jsonb, risk_thresholds),
                    weights = COALESCE(?::jsonb, weights),
                    other_params = COALESCE(?::jsonb, other_params)
                WHERE param_id = ?
                """,
                payload.paramName(),
                payload.segment(),
                payload.currentTimepoint(),
                payload.setName(),
                payload.waterQs(),
                payload.tidalLevel(),
                payload.benchId(),
                payload.refId(),
                payload.hs(),
                payload.hc(),
                payload.protectionLevel(),
                payload.controlLevel(),
                payload.comparisonTimepoint(),
                writeJson(payload.riskThresholds()),
                writeJson(payload.weights()),
                writeJson(payload.otherParams()),
                paramId);
    }

    public Map<String, Object> saveSection(String taskId, SectionPayload payload, boolean inheritFromBasicParam, boolean overwrite) {
        if (overwrite && exists("SELECT 1 FROM cross_sections WHERE section_id = ?", payload.sectionId())) {
            updateSection(payload.sectionId(), payload);
            return getSection(payload.sectionId());
        }

        Integer taskDbId = toInteger(getTask(taskId).get("id"));
        Integer basicParamId = payload.basicParamId();
        Map<String, Object> baseParams = inheritFromBasicParam && basicParamId != null
                ? jdbcTemplate.queryForObject("SELECT * FROM basic_params WHERE id = ?", mapRow(), basicParamId)
                : new LinkedHashMap<>();
        Map<String, Object> merged = mergeSectionParams(baseParams, payload);
        String geometryJson = writeJson(payload.geometry());

        jdbcTemplate.update(
                """
                INSERT INTO cross_sections (
                    task_id, section_id, section_name, bank_id, region_code, segment_index,
                    start_point, end_point, geom, section_geometry, distance, basic_param_id,
                    param_name, segment, current_timepoint, set_name, water_qs, tidal_level,
                    bench_id, ref_id, hs, hc, protection_level, control_level,
                    comparison_timepoint, risk_thresholds, weights, other_params
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ST_StartPoint(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)),
                    ST_EndPoint(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)),
                    ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?::jsonb, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?::jsonb, ?::jsonb, ?::jsonb
                )
                """,
                taskDbId,
                payload.sectionId(),
                payload.sectionName(),
                payload.bankId(),
                payload.regionCode(),
                merged.get("segment_index"),
                geometryJson,
                geometryJson,
                geometryJson,
                writeJson(payload.sectionGeometry()),
                merged.get("distance"),
                basicParamId,
                merged.get("param_name"),
                merged.get("segment"),
                merged.get("current_timepoint"),
                merged.get("set_name"),
                merged.get("water_qs"),
                merged.get("tidal_level"),
                merged.get("bench_id"),
                merged.get("ref_id"),
                merged.get("hs"),
                merged.get("hc"),
                merged.get("protection_level"),
                merged.get("control_level"),
                merged.get("comparison_timepoint"),
                writeJson(merged.get("risk_thresholds")),
                writeJson(merged.get("weights")),
                writeJson(merged.get("other_params")));
        return getSection(payload.sectionId());
    }

    public List<Map<String, Object>> listSections(String taskId, String bankId) {
        Integer taskDbId = taskId == null ? null : toInteger(getTask(taskId).get("id"));
        StringBuilder sql = new StringBuilder(
                """
                SELECT cs.*, t.task_id AS task_code, t.task_name, ST_AsGeoJSON(cs.geom)::jsonb AS geometry
                FROM cross_sections cs
                JOIN tasks t ON cs.task_id = t.id
                """);
        List<Object> args = new ArrayList<>();
        appendEqualsCondition(sql, args, "cs.task_id", taskDbId);
        appendEqualsCondition(sql, args, "cs.bank_id", bankId);
        sql.append(" ORDER BY cs.id");
        return jdbcTemplate.query(sql.toString(), mapRow(), args.toArray());
    }

    public Map<String, Object> getSection(String sectionId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                """
                SELECT cs.*, t.task_id AS task_code, t.task_name, ST_AsGeoJSON(cs.geom)::jsonb AS geometry
                FROM cross_sections cs
                JOIN tasks t ON cs.task_id = t.id
                WHERE cs.section_id = ?
                """,
                mapRow(),
                sectionId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
        return rows.getFirst();
    }

    public void updateSection(String sectionId, SectionPayload payload) {
        require(getSection(sectionId), "Section not found: " + sectionId);
        String geometryJson = payload.geometry() == null ? null : writeJson(payload.geometry());
        jdbcTemplate.update(
                """
                UPDATE cross_sections
                SET section_name = COALESCE(?, section_name),
                    bank_id = COALESCE(?, bank_id),
                    region_code = COALESCE(?, region_code),
                    segment_index = COALESCE(?, segment_index),
                    start_point = COALESCE(ST_StartPoint(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)), start_point),
                    end_point = COALESCE(ST_EndPoint(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)), end_point),
                    geom = COALESCE(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), geom),
                    section_geometry = COALESCE(?::jsonb, section_geometry),
                    distance = COALESCE(?, distance),
                    basic_param_id = COALESCE(?, basic_param_id),
                    param_name = COALESCE(?, param_name),
                    segment = COALESCE(?, segment),
                    current_timepoint = COALESCE(?, current_timepoint),
                    set_name = COALESCE(?, set_name),
                    water_qs = COALESCE(?, water_qs),
                    tidal_level = COALESCE(?, tidal_level),
                    bench_id = COALESCE(?, bench_id),
                    ref_id = COALESCE(?, ref_id),
                    hs = COALESCE(?, hs),
                    hc = COALESCE(?, hc),
                    protection_level = COALESCE(?, protection_level),
                    control_level = COALESCE(?, control_level),
                    comparison_timepoint = COALESCE(?, comparison_timepoint),
                    risk_thresholds = COALESCE(?::jsonb, risk_thresholds),
                    weights = COALESCE(?::jsonb, weights),
                    other_params = COALESCE(?::jsonb, other_params)
                WHERE section_id = ?
                """,
                payload.sectionName(),
                payload.bankId(),
                payload.regionCode(),
                payload.segmentIndex(),
                geometryJson,
                geometryJson,
                geometryJson,
                writeJson(payload.sectionGeometry()),
                payload.distance(),
                payload.basicParamId(),
                payload.paramName(),
                payload.segment(),
                payload.currentTimepoint(),
                payload.setName(),
                payload.waterQs(),
                payload.tidalLevel(),
                payload.benchId(),
                payload.refId(),
                payload.hs(),
                payload.hc(),
                payload.protectionLevel(),
                payload.controlLevel(),
                payload.comparisonTimepoint(),
                writeJson(payload.riskThresholds()),
                writeJson(payload.weights()),
                writeJson(payload.otherParams()),
                sectionId);
    }

    public void deleteSection(String sectionId) {
        if (jdbcTemplate.update("DELETE FROM cross_sections WHERE section_id = ?", sectionId) == 0) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
    }

    public Map<String, Object> getTaskFullData(String taskId) {
        return Map.of(
                "task", getTask(taskId),
                "sections", listSections(taskId, null));
    }

    public Map<String, Integer> clearTaskData(String taskId) {
        Integer taskDbId = toInteger(getTask(taskId).get("id"));
        Integer resultCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_risk_results WHERE task_id = ?", Integer.class, taskDbId);
        Integer sectionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cross_sections WHERE task_id = ?", Integer.class, taskDbId);
        jdbcTemplate.update("DELETE FROM bank_risk_results WHERE task_id = ?", taskDbId);
        jdbcTemplate.update("DELETE FROM cross_sections WHERE task_id = ?", taskDbId);
        return Map.of("sections", sectionCount == null ? 0 : sectionCount, "results", resultCount == null ? 0 : resultCount);
    }

    public void clearTaskResults(String taskId) {
        Integer taskDbId = toInteger(getTask(taskId).get("id"));
        jdbcTemplate.update("DELETE FROM bank_risk_results WHERE task_id = ?", taskDbId);
    }

    public List<Map<String, Object>> listRiskResults(String taskId, String bankId, String regionCode) {
        Integer taskDbId = taskId == null ? null : toInteger(getTask(taskId).get("id"));
        StringBuilder sql = new StringBuilder(
                """
                SELECT brr.*, ST_AsGeoJSON(brr.geom)::jsonb AS geometry
                FROM bank_risk_results brr
                """);
        List<Object> args = new ArrayList<>();
        appendEqualsCondition(sql, args, "brr.task_id", taskDbId);
        appendEqualsCondition(sql, args, "brr.bank_id", bankId);
        appendEqualsCondition(sql, args, "brr.region_code", regionCode);
        sql.append(" ORDER BY brr.id");
        return jdbcTemplate.query(sql.toString(), mapRow(), args.toArray());
    }

    public Map<String, Object> getRiskResultBySectionId(String sectionId) {
        Integer sectionDbId = getSectionDbId(sectionId);
        List<Map<String, Object>> rows = jdbcTemplate.query(
                """
                SELECT brr.*, ST_AsGeoJSON(brr.geom)::jsonb AS geometry
                FROM bank_risk_results brr
                WHERE brr.section_id = ?
                ORDER BY brr.id DESC
                LIMIT 1
                """,
                mapRow(),
                sectionDbId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Risk result not found for section_id: " + sectionId);
        }
        return rows.getFirst();
    }

    public Integer getTaskDbId(String taskId) {
        return toInteger(getTask(taskId).get("id"));
    }

    public Integer getSectionDbId(String sectionId) {
        return toInteger(getSection(sectionId).get("id"));
    }

    public List<Map<String, Object>> getSectionsByTask(String taskId) {
        return listSections(taskId, null);
    }

    public void saveRiskResult(
            Integer taskDbId,
            Integer sectionDbId,
            String sectionName,
            String regionCode,
            String bankId,
            Integer riskLevel,
            Map<String, Object> indicators,
            Map<String, Object> geometry) {
        jdbcTemplate.update(
                """
                INSERT INTO bank_risk_results (
                    task_id, section_id, section_name, region_code, bank_id,
                    risk_level, indicators, geom
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))
                """,
                taskDbId,
                sectionDbId,
                sectionName,
                regionCode,
                bankId,
                riskLevel,
                writeJson(indicators),
                writeJson(geometry));
    }

    private Map<String, Object> mergeSectionParams(Map<String, Object> baseParams, SectionPayload payload) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseParams != null) {
            merged.putAll(baseParams);
        }
        putIfNotNull(merged, "segment_index", payload.segmentIndex());
        putIfNotNull(merged, "distance", payload.distance());
        putIfNotNull(merged, "param_name", payload.paramName());
        putIfNotNull(merged, "segment", payload.segment());
        putIfNotNull(merged, "current_timepoint", payload.currentTimepoint());
        putIfNotNull(merged, "set_name", payload.setName());
        putIfNotNull(merged, "water_qs", payload.waterQs());
        putIfNotNull(merged, "tidal_level", payload.tidalLevel());
        putIfNotNull(merged, "bench_id", payload.benchId());
        putIfNotNull(merged, "ref_id", payload.refId());
        putIfNotNull(merged, "hs", payload.hs());
        putIfNotNull(merged, "hc", payload.hc());
        putIfNotNull(merged, "protection_level", payload.protectionLevel());
        putIfNotNull(merged, "control_level", payload.controlLevel());
        putIfNotNull(merged, "comparison_timepoint", payload.comparisonTimepoint());
        putIfNotNull(merged, "risk_thresholds", payload.riskThresholds());
        putIfNotNull(merged, "weights", payload.weights());
        putIfNotNull(merged, "other_params", payload.otherParams());
        return merged;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private void appendEqualsCondition(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value == null) {
            return;
        }
        sql.append(args.isEmpty() ? " WHERE " : " AND ")
                .append(column)
                .append(" = ?");
        args.add(value);
    }

    private boolean exists(String sql, Object value) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (" + sql + ") t", Integer.class, value);
        return count != null && count > 0;
    }

    private RowMapper<Map<String, Object>> mapRow() {
        return this::toMap;
    }

    private Map<String, Object> toMap(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int index = 1; index <= meta.getColumnCount(); index++) {
            String label = meta.getColumnLabel(index);
            Object value = rs.getObject(index);
            row.put(label, normalizeValue(value));
        }
        return row;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof PGobject pgObject) {
            if ("json".equalsIgnoreCase(pgObject.getType()) || "jsonb".equalsIgnoreCase(pgObject.getType())) {
                return readJson(pgObject.getValue());
            }
            return pgObject.getValue();
        }
        return value;
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception exception) {
            return json;
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write JSON", exception);
        }
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
