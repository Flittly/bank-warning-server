package com.yangtze.bankwarning.repository.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractJdbcRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    protected AbstractJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    protected List<Map<String, Object>> queryList(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        return statement.query(this::toMap).list();
    }

    protected Map<String, Object> queryOne(String sql, Map<String, Object> params) {
        List<Map<String, Object>> rows = queryList(sql, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected int update(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        return statement.update();
    }

    protected int queryInt(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        return statement.query(Integer.class).optional().orElse(0);
    }

    protected boolean exists(String sql, Map<String, Object> params) {
        return !queryList(sql, params).isEmpty();
    }

    protected Map<String, Object> params(String key, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(key, value);
        return params;
    }

    protected void appendEqualsCondition(StringBuilder sql, Map<String, Object> args, String column, String paramName, Object value) {
        if (value == null) {
            return;
        }
        sql.append(args.isEmpty() ? " WHERE " : " AND ")
                .append(column)
                .append(" = :")
                .append(paramName);
        args.put(paramName, value);
    }

    protected String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write JSON", exception);
        }
    }

    protected Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
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
}
