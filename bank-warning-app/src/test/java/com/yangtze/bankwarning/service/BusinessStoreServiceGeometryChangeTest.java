package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断面几何「变没变」的判断。
 *
 * <p>这段判断决定两件事：几何变了要不要作废旧结论（写错方向 = 界面继续用过期颜色），
 * 以及会不会白扔一次重算（写错方向 = 每改一次断面名就跑两次模型调用）。
 *
 * <p>关键在于**不能逐字符比**：{@code section_geometry} 存的是 jsonb，读回来是
 * PostgreSQL 规范化过的文本（键序、空白、数字写法都可能与前端发来的不同）。
 */
class BusinessStoreServiceGeometryChangeTest {

    private static Map<String, Object> geometry(double lng1, double lat1, double lng2, double lat2) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "LineString");
        line.put("coordinates", List.of(List.of(lng1, lat1), List.of(lng2, lat2)));
        return line;
    }

    @Test
    void identicalGeometryInDifferentKeyOrderIsUnchanged() {
        // jsonb 读回来键序不保证与写入一致
        String stored = "{\"coordinates\":[[120.1,31.2],[120.2,31.3]],\"type\":\"LineString\"}";
        assertFalse(BusinessStoreService.geometryChanged(stored, geometry(120.1, 31.2, 120.2, 31.3)));
    }

    @Test
    void whitespaceDifferenceIsUnchanged() {
        String stored = "{ \"type\" : \"LineString\" , \"coordinates\" : [ [120.1, 31.2], [120.2, 31.3] ] }";
        assertFalse(BusinessStoreService.geometryChanged(stored, geometry(120.1, 31.2, 120.2, 31.3)));
    }

    @Test
    void movedEndpointIsChanged() {
        String stored = "{\"type\":\"LineString\",\"coordinates\":[[120.1,31.2],[120.2,31.3]]}";
        assertTrue(BusinessStoreService.geometryChanged(stored, geometry(120.1, 31.2, 120.2, 31.3001)));
    }

    @Test
    void missingIncomingGeometryMeansNoChange() {
        // 只改名字/参数的 PUT 不带几何，绝不能因此把结果作废
        String stored = "{\"type\":\"LineString\",\"coordinates\":[[120.1,31.2],[120.2,31.3]]}";
        assertFalse(BusinessStoreService.geometryChanged(stored, null));
    }

    @Test
    void missingStoredGeometryCountsAsChange() {
        assertTrue(BusinessStoreService.geometryChanged(null, geometry(120.1, 31.2, 120.2, 31.3)));
        assertTrue(BusinessStoreService.geometryChanged("   ", geometry(120.1, 31.2, 120.2, 31.3)));
    }

    @Test
    void integerAndDecimalNodeTypesAreTreatedAsChanged() {
        // 已知边界：120 与 120.0 是不同节点类型。方向是安全的一侧 —— 多算一次，
        // 而不是留存一份可能对应旧位置的结论。
        String stored = "{\"type\":\"LineString\",\"coordinates\":[[120,31],[121,32]]}";
        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("type", "LineString");
        incoming.put("coordinates", List.of(List.of(120.0, 31.0), List.of(121.0, 32.0)));
        assertTrue(BusinessStoreService.geometryChanged(stored, incoming));
    }

    @Test
    void unparsableStoredGeometryDoesNotWipeResults() {
        // 脏数据不该触发「作废 + 重算」的连锁反应
        assertFalse(BusinessStoreService.geometryChanged("{not json", geometry(120.1, 31.2, 120.2, 31.3)));
    }
}
