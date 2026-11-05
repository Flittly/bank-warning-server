package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 只改岸段几何的请求体：{@code {"bank_geometry": {"type":"LineString","coordinates":[...]}}}。
 *
 * <p>刻意不叫 {@code BankPayload}：那个是全字段覆盖（要求 bank_name / region_code 非空），
 * 折点编辑只持有几何，两者混用会把岸段名写空。字段用 {@code bank_geometry} 而不是
 * {@code geometry}，与库列名、读接口返回的键名保持一致，避免前端在两套命名之间转换。
 */
public record BankGeometryUpdateRequest(
        @JsonProperty("bank_geometry") Map<String, Object> bankGeometry) {
}
