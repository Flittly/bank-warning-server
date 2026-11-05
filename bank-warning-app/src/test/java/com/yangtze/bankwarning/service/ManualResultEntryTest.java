package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.dto.RiskResultOverrideRequest;
import com.yangtze.bankwarning.domain.po.RiskResultPO;
import com.yangtze.bankwarning.domain.po.SectionPO;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「无结果断面人工录入」的三条不变量。
 *
 * <p>这三条都不是显而易见的，且都在实现过程中真错过：
 * <ol>
 *   <li>新建行的 edit_state 只能有 {@code created_manual}，<b>不能</b>有 {@code original_indicators} ——
 *       带一个 null 快照会让「撤销」把 indicators 写成 JSON null，看起来像一条空白结果；</li>
 *   <li>人工新建的行<b>再次编辑</b>时必须继续带着 {@code created_manual}，否则撤销语义
 *       从「删掉这行」退化成「恢复成第一次的人工值」（旧写法正是这么坏的）；</li>
 *   <li>撤销人工新建的行 = 软删，且返回 null，让前端把断面打回「无数据」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ManualResultEntryTest {

    private static final String SECTION_ID = "sec-task-x-5";
    private static final String TASK_ID = "task-x";

    @Mock private BankMapper bankMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private BasicParamMapper basicParamMapper;
    @Mock private SectionMapper sectionMapper;
    @Mock private RiskResultMapper riskResultMapper;
    @Mock private SectionProfileMapper sectionProfileMapper;

    @InjectMocks private BusinessStoreService service;

    /** 一个断面该有的最小信息（新建结果行要用它填 section_name / bank_id / task_id）。 */
    private static SectionPO section() {
        SectionPO po = new SectionPO();
        po.setSectionId(SECTION_ID);
        po.setSectionName("断面6");
        po.setTaskId(TASK_ID);
        po.setBankId("bank-a");
        po.setRegionCode("Mzs");
        return po;
    }

    /** 9 个指标齐活、风险值与等级自洽（默认阈值下 0.62 → 3 档）。 */
    private static RiskResultOverrideRequest request(Long resultId, String note) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("Ky", 1.5);
        raw.put("PQ", 0.8);
        raw.put("Zd", 0.2);
        raw.put("Sa", 0.1);
        raw.put("Ln", 0.05);
        raw.put("Zb", 25);
        raw.put("Dsed", 0.9);
        raw.put("PL", "normal");
        raw.put("LC", "normal");

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("raw_values", raw);
        patch.put("matrices", Map.of());
        patch.put("result", 0.62);
        patch.put("risk_level", 3);
        return new RiskResultOverrideRequest(TASK_ID, resultId, note, patch);
    }

    @Test
    void noResultSectionCreatesManualRow() {
        when(riskResultMapper.selectLatestBySectionId(anyString(), any())).thenReturn(null);
        when(sectionMapper.selectBySectionId(eq(SECTION_ID), any())).thenReturn(section());
        // 任务里没有可用参数快照 ⇒ 回落默认阈值（这里返回 null 即代表「取不到」）
        when(riskResultMapper.selectIndicatorsByTaskId(eq(TASK_ID), any())).thenReturn(null);

        service.overrideRiskResult(SECTION_ID, request(null, "人工补录"));

        ArgumentCaptor<RiskResultPO> captor = ArgumentCaptor.forClass(RiskResultPO.class);
        verify(riskResultMapper).insertManualOverride(captor.capture());
        RiskResultPO inserted = captor.getValue();

        assertEquals(TASK_ID, inserted.getTaskId());
        assertEquals(SECTION_ID, inserted.getSectionId());
        assertEquals("断面6", inserted.getSectionName());
        assertEquals("bank-a", inserted.getBankId());
        assertEquals(3, inserted.getRiskLevel());
        // 人工录入没有模型轮次，run_id 必须留空，否则在库里分不清来源
        assertNull(inserted.getRunId());

        Map<String, Object> indicators = JsonProbe.asMap(inserted.getIndicators());
        assertEquals(0.62, ((Number) indicators.get("result")).doubleValue(), 1e-9);
        assertNotNull(indicators.get("raw_values"));
        // 取不到任务参数时刻意不回带 thresholds —— 前端与后端都会回落默认值
        assertFalse(indicators.containsKey("thresholds"));

        Map<String, Object> editState = JsonProbe.asMap(inserted.getEditState());
        assertTrue(Boolean.parseBoolean(String.valueOf(editState.get("created_manual"))));
        assertFalse(editState.containsKey("original_indicators"),
                "人工新建的行不能写 original_indicators：null 快照会让撤销产出空白结果");
    }

    @Test
    void editingManualRowKeepsCreatedManualFlag() {
        RiskResultPO existing = new RiskResultPO();
        existing.setId(9001L);
        existing.setTaskId(TASK_ID);
        existing.setSectionId(SECTION_ID);
        existing.setRiskLevel(3);
        existing.setIndicators("{\"result\":0.62,\"raw_values\":{}}");
        existing.setEditState("{\"created_manual\":true,\"edited_at\":\"2026-10-26T10:00:00+08:00\"}");

        when(riskResultMapper.selectLatestBySectionId(anyString(), any())).thenReturn(existing);
        when(riskResultMapper.updateManualOverride(anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(1);

        service.overrideRiskResult(SECTION_ID, request(9001L, "再改一次"));

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(riskResultMapper).updateManualOverride(anyLong(), anyString(), any(), stateCaptor.capture(), any());

        Map<String, Object> editState = JsonProbe.asMap(stateCaptor.getValue());
        assertTrue(Boolean.parseBoolean(String.valueOf(editState.get("created_manual"))),
                "二次编辑丢掉 created_manual ⇒ 撤销会退化成「恢复成第一次的人工值」");
        assertFalse(editState.containsKey("original_indicators"),
                "人工新建的行没有模型原值，不该凭空造一份快照");
        assertEquals("再改一次", editState.get("note"));
    }

    @Test
    void revertingManualRowSoftDeletesAndReturnsNull() {
        RiskResultPO existing = new RiskResultPO();
        existing.setId(9001L);
        existing.setTaskId(TASK_ID);
        existing.setSectionId(SECTION_ID);
        existing.setEditState("{\"created_manual\":true}");

        // 第一次查得到这一行；软删之后同一断面就查不到了（真实行为），所以第二次返回 null
        when(riskResultMapper.selectLatestBySectionId(anyString(), any())).thenReturn(existing, (RiskResultPO) null);
        when(riskResultMapper.softDeleteById(eq(9001L), any())).thenReturn(1);

        assertNull(service.revertRiskResultOverride(SECTION_ID, TASK_ID));

        verify(riskResultMapper).softDeleteById(eq(9001L), any());
    }

    /** 只用来把 PO 里的 JSON 字符串摊成 Map，避免在断言里堆字符串匹配。 */
    private static final class JsonProbe {
        static Map<String, Object> asMap(String json) {
            try {
                Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(json, Object.class);
                Map<String, Object> out = new LinkedHashMap<>();
                if (parsed instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> out.put(String.valueOf(k), v));
                }
                return out;
            } catch (Exception e) {
                throw new IllegalStateException("测试数据不是合法 JSON: " + json, e);
            }
        }
    }

    /** 编译期确认 raw_values 的键与 Python 侧一致（少一个 key 会被 parseOverrideValues 拒）。 */
    @Test
    void rawValueKeysMatchPythonContract() {
        List<String> keys = List.of("Ky", "PQ", "Zd", "Sa", "Ln", "Zb", "Dsed", "PL", "LC");
        Map<String, Object> raw = (Map<String, Object>) request(null, null).patch().get("raw_values");
        assertEquals(keys.size(), raw.size());
        keys.forEach(k -> assertTrue(raw.containsKey(k), "缺指标 " + k));
    }
}
