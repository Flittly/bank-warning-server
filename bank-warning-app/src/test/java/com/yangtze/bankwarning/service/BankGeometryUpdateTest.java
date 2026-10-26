package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.exception.BankGeometryException;
import com.yangtze.bankwarning.domain.po.BankPO;
import com.yangtze.bankwarning.mapper.BankMapper;
import com.yangtze.bankwarning.mapper.BasicParamMapper;
import com.yangtze.bankwarning.mapper.RiskResultMapper;
import com.yangtze.bankwarning.mapper.SectionMapper;
import com.yangtze.bankwarning.mapper.SectionProfileMapper;
import com.yangtze.bankwarning.mapper.TaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 岸段折点保存的四条边界。
 *
 * <p>这些不是「防御性编程」式的洁癖，每一条都对应一种真会发生的坏结果：
 * <ol>
 *   <li>几何不是 LineString ⇒ 里程投影 / 色带 gradient / 按里程切片三处同时要加分支，
 *       现有一处都没加，静默画不出来；</li>
 *   <li>折点不足 2 个 ⇒ 前端画不出线，用户看到岸段直接消失；</li>
 *   <li>NaN / 越界坐标 ⇒ Mapbox 会**静默丢弃整条线**，没有任何报错；</li>
 *   <li>所有折点重合 ⇒ 长度 0，`lineSliceAlong` 抛错，色带整段消失。</li>
 * </ol>
 *
 * <p>另外守住两个返回值约定：影响面（task_count / section_count）必须回带，
 * 否则前端无从提示「这条岸段被几个任务共用」。
 */
@ExtendWith(MockitoExtension.class)
class BankGeometryUpdateTest {

    private static final String BANK_ID = "bank-Mzs-001";

    @Mock private BankMapper bankMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private BasicParamMapper basicParamMapper;
    @Mock private SectionMapper sectionMapper;
    @Mock private RiskResultMapper riskResultMapper;
    @Mock private SectionProfileMapper sectionProfileMapper;

    @InjectMocks private BusinessStoreService service;

    private static BankPO bank() {
        BankPO po = new BankPO();
        po.setBankId(BANK_ID);
        po.setBankName("池州段左岸");
        po.setReachGroup("Mzs");
        return po;
    }

    /** 构造 LineString；坐标用 List 是为了模拟 Jackson 反序列化后的真实形状。 */
    private static Map<String, Object> lineString(Object... lngLatPairs) {
        List<Object> coordinates = new ArrayList<>();
        for (Object pair : lngLatPairs) {
            coordinates.add(pair);
        }
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "LineString");
        geometry.put("coordinates", coordinates);
        return geometry;
    }

    private static List<Double> point(double lng, double lat) {
        List<Double> p = new ArrayList<>(2);
        p.add(lng);
        p.add(lat);
        return p;
    }

    @Test
    void 合法折点写入成功并回带影响面() {
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(bank());
        when(bankMapper.updateGeometry(eq(BANK_ID), anyString(), any())).thenReturn(1);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("task_count", 3L);
        usage.put("section_count", 240L);
        when(bankMapper.selectUsageByBankId(eq(BANK_ID), any())).thenReturn(usage);

        Map<String, Object> result = service.updateBankGeometry(
                BANK_ID, lineString(point(120.0, 32.0), point(120.1, 32.1)));

        assertEquals(BANK_ID, result.get("bank_id"));
        assertEquals(2, result.get("vertex_count"));
        // 影响面必须原样回带：岸段是用户级共享资源，前端靠它提示「会影响几个任务」
        assertEquals(3L, result.get("task_count"));
        assertEquals(240L, result.get("section_count"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(bankMapper).updateGeometry(eq(BANK_ID), json.capture(), any());
        assertTrue(json.getValue().contains("LineString"));
    }

    @Test
    void 整数坐标被规范化成小数落库() {
        // 前端偶尔传整数（120 而不是 120.0）。若不统一，同一个折点会在 JSON 里出现
        // 两种写法，比对签名时被当成不同值 ⇒ 「没改过」被误判成「改过」。
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(bank());
        when(bankMapper.updateGeometry(eq(BANK_ID), anyString(), any())).thenReturn(1);

        Map<String, Object> geometry = lineString(
                List.of(120, 32), List.of(120.1, 32.1));
        service.updateBankGeometry(BANK_ID, geometry);

        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) geometry.get("coordinates");
        assertTrue(normalized.get(0) instanceof List<?> first
                && first.get(0) instanceof Double, "坐标应被写成 double");
        assertEquals(120.0, ((List<?>) normalized.get(0)).get(0));
    }

    @Test
    void 非_LineString_一律拒绝() {
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(bank());

        Map<String, Object> polygon = new LinkedHashMap<>();
        polygon.put("type", "Polygon");
        polygon.put("coordinates", List.of(List.of(point(120, 32), point(120.1, 32.1))));

        BankGeometryException ex = assertThrows(BankGeometryException.class,
                () -> service.updateBankGeometry(BANK_ID, polygon));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_BANK_GEOMETRY", ex.getCode());
        verify(bankMapper, never()).updateGeometry(anyString(), anyString(), any());
    }

    @Test
    void 折点少于两个被拒绝() {
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(bank());

        BankGeometryException ex = assertThrows(BankGeometryException.class,
                () -> service.updateBankGeometry(BANK_ID, lineString(point(120.0, 32.0))));
        assertEquals("INVALID_BANK_GEOMETRY", ex.getCode());
        verify(bankMapper, never()).updateGeometry(anyString(), anyString(), any());
    }

    @Test
    void 非有限数与越界坐标都被拒绝() {
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(bank());

        // NaN 一旦入库，Mapbox 会静默丢弃整条线 —— 宁可 400，也不要留一条画不出来的岸段
        assertThrows(BankGeometryException.class, () -> service.updateBankGeometry(
                BANK_ID, lineString(point(Double.NaN, 32.0), point(120.1, 32.1))));
        assertThrows(BankGeometryException.class, () -> service.updateBankGeometry(
                BANK_ID, lineString(point(120.0, 32.0), point(120.1, 95.0))));
        assertThrows(BankGeometryException.class, () -> service.updateBankGeometry(
                BANK_ID, lineString(point(200.0, 32.0), point(120.1, 32.1))));

        verify(bankMapper, never()).updateGeometry(anyString(), anyString(), any());
    }

    @Test
    void 所有折点重合被拒绝() {
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(bank());

        BankGeometryException ex = assertThrows(BankGeometryException.class, () -> service.updateBankGeometry(
                BANK_ID, lineString(point(120.0, 32.0), point(120.0, 32.0))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(bankMapper, never()).updateGeometry(anyString(), anyString(), any());
    }

    @Test
    void 岸段不存在返回_404_而不是_400() {
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(null);

        BankGeometryException ex = assertThrows(BankGeometryException.class,
                () -> service.updateBankGeometry(BANK_ID, lineString(point(120, 32), point(120.1, 32.1))));
        // 404 与 400 的区分是给前端的：400 要回滚这一次改动、留在编辑态；404 要退出编辑
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("BANK_NOT_FOUND", ex.getCode());
    }

    @Test
    void 查询影响面时顺带回带岸段名称() {
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(bank());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("task_count", 5L);
        usage.put("section_count", 12L);
        when(bankMapper.selectUsageByBankId(eq(BANK_ID), any())).thenReturn(usage);

        Map<String, Object> result = service.getBankUsage(BANK_ID);

        // 带名称是为了让前端编辑浮栏不必再请求一次 /banks/{id}（那条会返回整段几何）
        assertEquals("池州段左岸", result.get("bank_name"));
        assertEquals(5L, result.get("task_count"));
        assertEquals(12L, result.get("section_count"));
    }

    @Test
    void 查询不存在的岸段返回_404() {
        when(bankMapper.selectByBankId(eq(BANK_ID), any())).thenReturn(null);
        BankGeometryException ex = assertThrows(BankGeometryException.class,
                () -> service.getBankUsage(BANK_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertNotNull(ex.getMessage());
    }
}
