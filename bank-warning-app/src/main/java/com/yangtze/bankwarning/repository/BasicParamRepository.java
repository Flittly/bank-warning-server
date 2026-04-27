package com.yangtze.bankwarning.repository;

import com.yangtze.bankwarning.dto.BasicParamPayload;
import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class BasicParamRepository extends AbstractJdbcRepository {

    public BasicParamRepository(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    public Map<String, Object> save(BasicParamPayload payload, boolean overwrite) {
        if (overwrite && exists("SELECT 1 FROM basic_params WHERE param_id = :paramId", params("paramId", payload.paramId()))) {
            update(payload.paramId(), payload);
            return getByParamId(payload.paramId());
        }
        Map<String, Object> args = toPayloadParams(payload);
        update(
                """
                INSERT INTO basic_params (
                    param_id, param_name, segment, current_timepoint, set_name, water_qs, tidal_level,
                    bench_id, ref_id, hs, hc, protection_level, control_level,
                    comparison_timepoint, risk_thresholds, weights, other_params
                ) VALUES (
                    :paramId, :paramName, :segment, :currentTimepoint, :setName, :waterQs, :tidalLevel,
                    :benchId, :refId, :hs, :hc, :protectionLevel, :controlLevel,
                    :comparisonTimepoint, CAST(:riskThresholds AS jsonb), CAST(:weights AS jsonb), CAST(:otherParams AS jsonb)
                )
                """,
                args);
        return getByParamId(payload.paramId());
    }

    public List<Map<String, Object>> list() {
        return queryList("SELECT * FROM basic_params ORDER BY id DESC", Map.of());
    }

    public Map<String, Object> getByParamId(String paramId) {
        return queryOne("SELECT * FROM basic_params WHERE param_id = :paramId", params("paramId", paramId));
    }

    public Map<String, Object> getById(Integer id) {
        return queryOne("SELECT * FROM basic_params WHERE id = :id", params("id", id));
    }

    public void update(String paramId, BasicParamPayload payload) {
        Map<String, Object> args = toPayloadParams(payload);
        args.put("paramId", paramId);
        update(
                """
                UPDATE basic_params
                SET param_name = COALESCE(:paramName, param_name),
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
                WHERE param_id = :paramId
                """,
                args);
    }

    private Map<String, Object> toPayloadParams(BasicParamPayload payload) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("paramId", payload.paramId());
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
        return args;
    }
}
