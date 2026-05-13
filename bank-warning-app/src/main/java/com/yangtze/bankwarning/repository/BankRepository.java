package com.yangtze.bankwarning.repository;

import com.yangtze.bankwarning.dto.BankPayload;
import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class BankRepository extends AbstractJdbcRepository {

    public BankRepository(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    public Map<String, Object> save(BankPayload payload, boolean overwrite) {
        if (overwrite && exists("SELECT 1 FROM banks WHERE bank_id = :bankId AND deleted_at IS NULL", params("bankId", payload.bankId()))) {
            update(payload.bankId(), payload);
            return getByBankId(payload.bankId());
        }

        String geometryJson = writeJson(payload.geometry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("bankId", payload.bankId());
        args.put("bankName", payload.bankName());
        args.put("regionCode", payload.regionCode());
        args.put("geometryJson", geometryJson);
        args.put("bankGeometry", writeJson(payload.bankGeometry()));
        args.put("description", payload.description());
        args.put("reversed", payload.reversed() != null && payload.reversed());
        update(
                """
                INSERT INTO banks (
                    bank_id, bank_name, region_code,
                    start_point, end_point, geom, bank_geometry, description, reversed
                ) VALUES (
                    :bankId, :bankName, :regionCode,
                    ST_StartPoint(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326)),
                    ST_EndPoint(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326)),
                    ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326),
                    CAST(:bankGeometry AS jsonb), :description, :reversed
                )
                """,
                args);
        return getByBankId(payload.bankId());
    }

    public List<Map<String, Object>> list(String regionCode) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT b.*, ST_AsGeoJSON(b.geom)::jsonb AS geometry
                FROM banks b
                WHERE b.deleted_at IS NULL
                """);
        Map<String, Object> args = new LinkedHashMap<>();
        appendEqualsCondition(sql, args, "b.region_code", "regionCode", regionCode);
        sql.append(" ORDER BY b.id");
        return queryList(sql.toString(), args);
    }

    public Map<String, Object> getByBankId(String bankId) {
        return queryOne(
                """
                SELECT b.*, ST_AsGeoJSON(b.geom)::jsonb AS geometry
                FROM banks b WHERE b.bank_id = :bankId AND b.deleted_at IS NULL
                """,
                params("bankId", bankId));
    }

    public void update(String bankId, BankPayload payload) {
        String geometryJson = writeJson(payload.geometry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("bankName", payload.bankName());
        args.put("regionCode", payload.regionCode());
        args.put("geometryJson", geometryJson);
        args.put("bankGeometry", writeJson(payload.bankGeometry()));
        args.put("description", payload.description());
        args.put("reversed", payload.reversed() != null && payload.reversed());
        args.put("bankId", bankId);
        update(
                """
                UPDATE banks
                SET bank_name = :bankName,
                    region_code = :regionCode,
                    start_point = ST_StartPoint(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326)),
                    end_point = ST_EndPoint(ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326)),
                    geom = ST_SetSRID(ST_GeomFromGeoJSON(:geometryJson), 4326),
                    bank_geometry = CAST(:bankGeometry AS jsonb),
                    description = :description,
                    reversed = :reversed
                WHERE bank_id = :bankId
                """,
                args);
    }

    public int deleteByBankId(String bankId) {
        return update("UPDATE banks SET deleted_at = CURRENT_TIMESTAMP WHERE bank_id = :bankId AND deleted_at IS NULL", params("bankId", bankId));
    }
}
