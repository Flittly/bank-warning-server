package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonUtil {
    static final ObjectMapper MAPPER = new ObjectMapper();

    static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, Object.class); } catch (Exception e) { return json; }
    }
}
