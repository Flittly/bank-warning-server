package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.exception.SectionManualInvalidException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「人工无效断面」的四条不变量。
 *
 * <p>每一条都对应一种真会发生的坏结果，不是防御性洁癖：
 * <ol>
 *   <li><b>invalid 缺失必须报错</b> —— 用 {@code boolean} 接 JSON 时，漏传字段会静默变成
 *       {@code false}，而 {@code false} 恰好是「恢复有效」：一个写错的请求会把用户刚标好的
 *       无效断面全部复活，图上毫无变化，事后无法追溯；</li>
 *   <li><b>恢复有效必须清空原因</b> —— 否则库里出现「有效但带着一条无效原因」的自相矛盾行，
 *       下次展示时会让人以为标记没生效；</li>
 *   <li><b>id 要 trim/去空/去重</b> —— 前端批量操作时 ids 来自 DOM 文本，容易带空格或重复；
 *       重复 id 会让 SQL 的 IN 列表里出现重复项，返回的 affected rows 也就跟着虚高；</li>
 *   <li><b>0 行更新不能算成功</b> —— 越权或断面已删时 Spring 会返回 0；这里原样返回，
 *       由 controller 带回去让前端提示，绝不吞掉。</li>
 * </ol>
 *
 * <p>另外守一条展示口径：单条结果 payload 的两个分支都必须回带 {@code manual_invalid} ——
 * 弹窗靠它决定按钮文案与「已标记无效」标签，漏一个分支就会出现「有结果的断面看不出被标了」。
 */
@ExtendWith(MockitoExtension.class)
class SectionManualInvalidTest {

    private static final String SEC_A = "sec-task-x-1";
    private static final String SEC_B = "sec-task-x-2";

    @Mock private BankMapper bankMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private BasicParamMapper basicParamMapper;
    @Mock private SectionMapper sectionMapper;
    @Mock private RiskResultMapper riskResultMapper;
    @Mock private SectionProfileMapper sectionProfileMapper;

    @InjectMocks private BusinessStoreService service;

    private static SectionPO section(String sectionId, boolean manualInvalid) {
        SectionPO po = new SectionPO();
        po.setSectionId(sectionId);
        po.setTaskId("task-x");
        po.setSectionName("断面 " + sectionId);
        po.setBankId("bank-1");
        po.setManualInvalid(manualInvalid);
        po.setManualInvalidReason(manualInvalid ? "线位偏移" : null);
        return po;
    }

    @Test
    void 标记无效时原样下发原因并去空格() {
        when(sectionMapper.updateManualInvalid(any(), eq(true), eq("线位偏移"), isNull())).thenReturn(1);

        int updated = service.setSectionsManualInvalid(List.of(SEC_A), true, "  线位偏移  ");

        assertEquals(1, updated);
        // userId 为 null = admin 不过滤（SecurityUtils 在无安全上下文时返回 null）
        verify(sectionMapper).updateManualInvalid(List.of(SEC_A), true, "线位偏移", null);
    }

    @Test
    void 恢复有效时原因一律清空() {
        when(sectionMapper.updateManualInvalid(any(), eq(false), isNull(), isNull())).thenReturn(1);

        // 即使前端把上次的原因一起传上来，恢复有效也不能把它写回去：
        // 「有效 + 无效原因」是自相矛盾的数据，展示时会被读成"标记没生效"。
        service.setSectionsManualInvalid(List.of(SEC_A), false, "线位偏移");

        verify(sectionMapper).updateManualInvalid(List.of(SEC_A), false, null, null);
    }

    @Test
    void invalid_缺失直接报错且不写库() {
        SectionManualInvalidException ex = assertThrows(SectionManualInvalidException.class,
                () -> service.setSectionsManualInvalid(List.of(SEC_A), null, null));
        assertTrue(ex.getMessage().contains("invalid"));
        assertEquals(400, ex.getStatus().value());
        verify(sectionMapper, never()).updateManualInvalid(any(), any(Boolean.class), any(), any());
    }

    @Test
    void 空断面列表直接报错() {
        assertThrows(SectionManualInvalidException.class,
                () -> service.setSectionsManualInvalid(List.of(), true, null));
        assertThrows(SectionManualInvalidException.class,
                () -> service.setSectionsManualInvalid(null, true, null));
        verify(sectionMapper, never()).updateManualInvalid(any(), any(Boolean.class), any(), any());
    }

    @Test
    void 断面id会去空去重去首尾空格() {
        when(sectionMapper.updateManualInvalid(any(), eq(true), any(), isNull())).thenReturn(2);

        List<String> messy = new ArrayList<>(Arrays.asList(SEC_A, "  " + SEC_B + "  ", SEC_A, "", null));
        service.setSectionsManualInvalid(messy, true, null);

        // 重复的 SEC_A 只能出现一次，否则 affected rows 虚高、前端会误以为多改了几条
        verify(sectionMapper).updateManualInvalid(List.of(SEC_A, SEC_B), true, null, null);
    }

    @Test
    void 原因超长被拒绝() {
        String tooLong = "长".repeat(201);
        SectionManualInvalidException ex = assertThrows(SectionManualInvalidException.class,
                () -> service.setSectionsManualInvalid(List.of(SEC_A), true, tooLong));
        assertTrue(ex.getMessage().contains("200"));
        verify(sectionMapper, never()).updateManualInvalid(any(), any(Boolean.class), any(), any());
    }

    @Test
    void 一条都没匹配上时如实返回零() {
        // 越权 / 断面已被删：SQL 影响 0 行。这里不能改写成"成功"，
        // 否则界面上按钮状态变了、图上却没变，用户只会以为"标了但没生效"。
        when(sectionMapper.updateManualInvalid(any(), eq(true), any(), isNull())).thenReturn(0);

        assertEquals(0, service.setSectionsManualInvalid(List.of(SEC_A), true, null));
    }

    @Test
    void 有结果的断面也回带人工无效标记() {
        RiskResultPO result = new RiskResultPO();
        result.setId(9001L);
        result.setSectionId(SEC_A);
        result.setRiskLevel(3);
        when(riskResultMapper.selectLatestBySectionId(eq(SEC_A), any())).thenReturn(result);
        when(sectionMapper.selectBySectionId(eq(SEC_A), any())).thenReturn(section(SEC_A, true));

        Map<String, Object> payload = service.getRiskResultPayload(SEC_A);

        assertEquals("computed", payload.get("status"));
        // 关键：有结果 ≠ 有效。标记是断面属性，必须有结果也要带回来。
        assertEquals(true, payload.get("manual_invalid"));
        assertEquals("线位偏移", payload.get("manual_invalid_reason"));
    }

    @Test
    void 无结果的断面也回带人工无效标记() {
        when(riskResultMapper.selectLatestBySectionId(eq(SEC_A), any())).thenReturn(null);
        when(sectionMapper.selectBySectionId(eq(SEC_A), any())).thenReturn(section(SEC_A, true));
        when(riskResultMapper.selectIndicatorsByTaskId(any(), any())).thenReturn(null);

        Map<String, Object> payload = service.getRiskResultPayload(SEC_A);

        assertEquals("not_computed", payload.get("status"));
        assertEquals(true, payload.get("manual_invalid"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sectionInfo = (Map<String, Object>) payload.get("section");
        assertEquals(true, sectionInfo.get("manual_invalid"));
    }

    @Test
    void 未标记的断面回带_false_而不是_null() {
        RiskResultPO result = new RiskResultPO();
        result.setSectionId(SEC_A);
        when(riskResultMapper.selectLatestBySectionId(eq(SEC_A), any())).thenReturn(result);
        when(sectionMapper.selectBySectionId(eq(SEC_A), any())).thenReturn(section(SEC_A, false));

        Map<String, Object> payload = service.getRiskResultPayload(SEC_A);

        // 前端只做 `=== true` 判断，但 null 会让 JSON 里多一种形态；
        // 统一成 boolean，省掉"三种假值"的坑。
        assertEquals(false, payload.get("manual_invalid"));
        assertNull(payload.get("manual_invalid_reason"));
    }
}
