package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.dto.TaskCopyRequest;
import com.yangtze.bankwarning.domain.dto.TaskCopyResponse;
import com.yangtze.bankwarning.domain.exception.TaskCopyException;
import com.yangtze.bankwarning.domain.po.BasicParamPO;
import com.yangtze.bankwarning.domain.po.TaskPO;
import com.yangtze.bankwarning.mapper.BankMapper;
import com.yangtze.bankwarning.mapper.BasicParamMapper;
import com.yangtze.bankwarning.mapper.RiskResultMapper;
import com.yangtze.bankwarning.mapper.SectionMapper;
import com.yangtze.bankwarning.mapper.SectionProfileMapper;
import com.yangtze.bankwarning.mapper.TaskMapper;
import com.yangtze.bankwarning.security.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务复制：只测「规则」，不测 SQL（SQL 的形状由 `INSERT … SELECT` 保证，靠真库验收）。
 *
 * <p>这里锁的是那些一旦写错、用户当场就会看见的不变量：
 * 副本不覆盖原件（id 全新）、状态不复制成 running、结果默认不搬、
 * 占位岸段与失效岸段被剔掉、名称超长不炸库、跨用户被拒。
 */
@ExtendWith(MockitoExtension.class)
class TaskCopyServiceTest {

    private static final String SRC = "task-1792030029572";

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private BankMapper bankMapper;
    @Mock
    private BasicParamMapper basicParamMapper;
    @Mock
    private SectionMapper sectionMapper;
    @Mock
    private RiskResultMapper riskResultMapper;
    @Mock
    private SectionProfileMapper sectionProfileMapper;
    @Mock
    private BusinessStoreService businessStoreService;

    private TaskCopyService service;

    @BeforeEach
    void setUp() {
        service = new TaskCopyService(taskMapper, bankMapper, basicParamMapper, sectionMapper,
                riskResultMapper, sectionProfileMapper, businessStoreService);
        authenticate(4L, "ROLE_USER");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 标识与状态 ====================

    @Test
    void copyGeneratesFreshTaskIdAndNeverCarriesRuntimeState() {
        stubSource(source("pending", "[\"b1\"]"));
        when(bankMapper.selectExistingBankIds(anyList(), any())).thenReturn(List.of("b1"));
        when(businessStoreService.copyTaskParamsInto(anyString(), anyString(), any()))
                .thenReturn(new BusinessStoreService.TaskParamsCopy(paramRow(7), "task"));
        when(sectionMapper.copySectionsFromTask(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(3);
        when(sectionMapper.selectDistinctRealBankIdsByTask(anyString(), any())).thenReturn(List.of("b1"));

        TaskCopyResponse response = service.copy(SRC, null);

        ArgumentCaptor<TaskPO> captor = ArgumentCaptor.forClass(TaskPO.class);
        verify(taskMapper).insert(captor.capture());
        TaskPO inserted = captor.getValue();

        assertTrue(inserted.getTaskId().startsWith("task-"));
        assertFalse(inserted.getTaskId().equals(SRC), "副本必须换 id，否则等于覆盖原件");
        assertEquals(SRC, inserted.getSourceTaskId());
        assertNotNull(inserted.getCopiedAt());
        // status / run_* 一律不写：交给 DB 默认值 pending / NULL。
        // 复制成 running 会让结果页无限轮询，且 /cancel 会把副本判成僵尸。
        assertNull(inserted.getStatus());
        assertNull(inserted.getRunStartedAt());
        assertNull(inserted.getRunCompletedAt());
        assertEquals(3, response.counts().get("sections"));
        assertFalse(response.idempotent());
    }

    @Test
    void defaultNameAppendsCopySuffix() {
        stubSource(source("pending", "[]"));
        stubEmptyCopy();

        TaskCopyResponse response = service.copy(SRC, new TaskCopyRequest(null, null, null, null, null, null));

        assertEquals("岸线分析任务 - 副本", response.taskName());
    }

    @Test
    void truncateKeepsSuffixWhenNameTooLong() {
        String longName = "沿".repeat(120);
        String truncated = TaskCopyService.truncateTaskName(longName);

        assertEquals(100, truncated.length());
        assertEquals(longName.substring(0, 100), truncated);

        String withSuffix = TaskCopyService.truncateTaskName(longName + " - 副本");
        assertEquals(100, withSuffix.length());
        assertTrue(withSuffix.endsWith(" - 副本"), "截断必须保住后缀，否则用户分不清哪条是副本");
    }

    @Test
    void invalidTargetTaskIdRejected() {
        stubSource(source("pending", "[]"));

        TaskCopyException ex = assertThrows(TaskCopyException.class,
                () -> service.copy(SRC, new TaskCopyRequest(null, null, null, null, "not-a-uuid", null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_COPY_REQUEST", ex.getCode());
    }

    @Test
    void targetTaskIdEqualToSourceRejected() {
        stubSource(source("pending", "[]"));

        TaskCopyException ex = assertThrows(TaskCopyException.class,
                () -> service.copy(SRC, new TaskCopyRequest(null, null, null, null, SRC, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // ==================== 幂等与冲突 ====================

    @Test
    void occupiedTargetIdWithOtherSourceIsConflict() {
        stubSource(source("pending", "[]"));
        TaskPO occupied = new TaskPO();
        occupied.setTaskId("task-11111111-1111-1111-1111-111111111111");
        occupied.setSourceTaskId("task-other");
        when(taskMapper.selectByTaskIdIncludingDeleted(anyString(), any())).thenReturn(occupied);

        TaskCopyException ex = assertThrows(TaskCopyException.class,
                () -> service.copy(SRC, new TaskCopyRequest(null, null, null, null,
                        "task-11111111-1111-1111-1111-111111111111", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("TASK_ID_CONFLICT", ex.getCode());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void retryWithSameTargetIdIsIdempotent() {
        when(taskMapper.selectByTaskId(eq(SRC), any())).thenReturn(source("pending", "[]"));
        String target = "task-22222222-2222-2222-2222-222222222222";
        TaskPO occupied = new TaskPO();
        occupied.setTaskId(target);
        occupied.setTaskName("副本");
        occupied.setSourceTaskId(SRC);
        when(taskMapper.selectByTaskIdIncludingDeleted(eq(target), any())).thenReturn(occupied);
        when(sectionMapper.countByTaskId(eq(target), any())).thenReturn(12);
        when(basicParamMapper.selectByTaskId(eq(target), any())).thenReturn(paramRow(5));

        TaskCopyResponse response = service.copy(SRC,
                new TaskCopyRequest(null, null, null, null, target, null));

        assertTrue(response.idempotent());
        assertEquals(target, response.taskId());
        assertEquals(12, response.counts().get("sections"));
        assertEquals(1, response.counts().get("params"));
        verify(taskMapper, never()).insert(any());
        verify(sectionMapper, never()).copySectionsFromTask(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    // ==================== 运行中 / 结果复制 ====================

    @Test
    void runningSourceWithResultsRequestIsRejected() {
        stubSource(source("running", "[]"));

        TaskCopyException ex = assertThrows(TaskCopyException.class,
                () -> service.copy(SRC, new TaskCopyRequest(null, true, null, null, null, null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("SOURCE_RUNNING", ex.getCode());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void runningSourceConfigOnlyCopyAddsWarning() {
        stubSource(source("running", "[]"));
        stubEmptyCopy();

        TaskCopyResponse response = service.copy(SRC, null);

        assertTrue(hasWarning(response, "SOURCE_RUNNING"));
        assertFalse(response.warnings().isEmpty());
        verify(riskResultMapper, never()).copyFromTask(anyString(), anyString(), anyString(), any());
    }

    @Test
    void resultsAreNotCopiedByDefault() {
        stubSource(source("completed", "[]"));
        stubEmptyCopy();

        TaskCopyResponse response = service.copy(SRC, null);

        assertEquals(0, response.counts().get("results"));
        assertEquals(0, response.counts().get("profiles"));
        verify(riskResultMapper, never()).copyFromTask(anyString(), anyString(), anyString(), any());
        verify(sectionProfileMapper, never()).copyFromTask(anyString(), anyString(), anyString(), any());
    }

    @Test
    void includeResultsCopiesResultsAndProfiles() {
        stubSource(source("completed", "[]"));
        stubEmptyCopy();
        when(riskResultMapper.copyFromTask(anyString(), anyString(), anyString(), any())).thenReturn(302);
        when(sectionProfileMapper.copyFromTask(anyString(), anyString(), anyString(), any())).thenReturn(302);

        TaskCopyResponse response = service.copy(SRC,
                new TaskCopyRequest(null, true, null, null, null, null));

        assertEquals(302, response.counts().get("results"));
        assertEquals(302, response.counts().get("profiles"));
        // 前缀必须是 `sec-<源 taskId>`，SQL 里靠它切片出原后缀
        verify(riskResultMapper).copyFromTask(eq(SRC), anyString(), eq("sec-" + SRC), any());
    }

    // ==================== 岸段 / 参数 ====================

    @Test
    void syntheticAndMissingBanksAreDroppedWithWarning() {
        stubSource(source("pending", "[\"b1\",\"line-3\",\"gone\"]"));
        when(bankMapper.selectExistingBankIds(anyList(), any())).thenReturn(List.of("b1"));
        when(businessStoreService.copyTaskParamsInto(anyString(), anyString(), any()))
                .thenReturn(new BusinessStoreService.TaskParamsCopy(paramRow(7), "task"));
        when(sectionMapper.copySectionsFromTask(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(1);
        when(sectionMapper.selectDistinctRealBankIdsByTask(anyString(), any())).thenReturn(List.of());

        TaskCopyResponse response = service.copy(SRC, null);

        ArgumentCaptor<TaskPO> captor = ArgumentCaptor.forClass(TaskPO.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals("[\"b1\"]", captor.getValue().getBankIds());
        assertTrue(hasWarning(response, "BANK_MISSING"));
    }

    @Test
    void bankIdsAreBackfilledFromCopiedSections() {
        stubSource(source("pending", "[\"b1\"]"));
        when(bankMapper.selectExistingBankIds(anyList(), any())).thenReturn(List.of("b1"));
        when(businessStoreService.copyTaskParamsInto(anyString(), anyString(), any()))
                .thenReturn(new BusinessStoreService.TaskParamsCopy(paramRow(7), "task"));
        when(sectionMapper.copySectionsFromTask(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(5);
        // 源 bank_ids 缺项（历史数据「有断面没登记」），副本要按断面补齐
        when(sectionMapper.selectDistinctRealBankIdsByTask(anyString(), any())).thenReturn(List.of("b1", "b9"));

        service.copy(SRC, null);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).updateBankIds(anyString(), json.capture(), any());
        assertTrue(json.getValue().contains("b9"));
    }

    @Test
    void materializedParamsRaiseExplainingWarning() {
        stubSource(source("pending", "[]"));
        when(businessStoreService.copyTaskParamsInto(anyString(), anyString(), any()))
                .thenReturn(new BusinessStoreService.TaskParamsCopy(paramRow(9), "section"));
        when(sectionMapper.copySectionsFromTask(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(2);
        when(sectionMapper.selectDistinctRealBankIdsByTask(anyString(), any())).thenReturn(List.of());

        TaskCopyResponse response = service.copy(SRC, null);

        assertTrue(hasWarning(response, "PARAM_MATERIALIZED"));
        assertEquals(1, response.counts().get("params"));
    }

    @Test
    void includeParamsFalseSkipsParamRowAndPassesNullParamId() {
        stubSource(source("pending", "[]"));
        when(sectionMapper.copySectionsFromTask(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(2);
        when(sectionMapper.selectDistinctRealBankIdsByTask(anyString(), any())).thenReturn(List.of());

        TaskCopyResponse response = service.copy(SRC,
                new TaskCopyRequest(null, null, false, null, null, null));

        assertEquals(0, response.counts().get("params"));
        verify(businessStoreService, never()).copyTaskParamsInto(anyString(), anyString(), any());
        verify(sectionMapper).copySectionsFromTask(eq(SRC), anyString(), anyString(), isNull(), any(), eq(false));
    }

    @Test
    void resetValidationPropagatesToSectionCopy() {
        stubSource(source("pending", "[]"));
        when(businessStoreService.copyTaskParamsInto(anyString(), anyString(), any()))
                .thenReturn(new BusinessStoreService.TaskParamsCopy(paramRow(7), "task"));
        when(sectionMapper.copySectionsFromTask(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(1);
        when(sectionMapper.selectDistinctRealBankIdsByTask(anyString(), any())).thenReturn(List.of());

        service.copy(SRC, new TaskCopyRequest(null, null, null, true, null, null));

        verify(sectionMapper).copySectionsFromTask(eq(SRC), anyString(), anyString(), any(), any(), eq(true));
    }

    // ==================== 权限 ====================

    @Test
    void crossUserCopyIsForbidden() {
        stubSource(source("pending", "[]"));

        TaskCopyException ex = assertThrows(TaskCopyException.class,
                () -> service.copy(SRC, new TaskCopyRequest(null, null, null, null, null, 99L)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void missingSourceTaskIsNotFound() {
        when(taskMapper.selectByTaskId(eq(SRC), any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.copy(SRC, null));
    }

    // ==================== 参数 JSON 清洗 ====================

    @Test
    void stripDefaultTemplateFlagRemovesOnlyThatKey() {
        BusinessStoreService bare = new BusinessStoreService(null, null, null, null, null, null);

        String stripped = bare.stripDefaultTemplateFlag("{\"is_default\":true,\"note\":\"x\"}");
        assertFalse(stripped.contains("is_default"));
        assertTrue(stripped.contains("note"));

        // 不带该键 ⇒ 原样返回（不做「顺手美化」）
        assertEquals("{\"note\":\"x\"}", bare.stripDefaultTemplateFlag("{\"note\":\"x\"}"));
        // 非法 JSON / 非对象 ⇒ 原样返回，不抛
        assertEquals("not-json", bare.stripDefaultTemplateFlag("not-json"));
        assertEquals("[1,2]", bare.stripDefaultTemplateFlag("[1,2]"));
        assertNull(bare.stripDefaultTemplateFlag(null));
    }

    // ==================== 辅助 ====================

    private void authenticate(long userId, String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        var principal = new CustomUserDetails(userId, "user" + userId, "", authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private TaskPO source(String status, String bankIds) {
        TaskPO po = new TaskPO();
        po.setTaskId(SRC);
        po.setTaskName("岸线分析任务");
        po.setDescription("原始任务");
        po.setStatus(status);
        po.setBankIds(bankIds);
        po.setUserId(4L);
        return po;
    }

    private BasicParamPO paramRow(int id) {
        BasicParamPO po = new BasicParamPO();
        po.setId((long) id);
        po.setParamId("tp-" + SRC);
        return po;
    }

    /** 源任务读得到、目标 id 未被占、参数与断面复制都成功的最小桩。 */
    private void stubSource(TaskPO source) {
        when(taskMapper.selectByTaskId(eq(SRC), any())).thenReturn(source);
        // 有些用例在「定 id」阶段就抛异常（非法 id / 越权），还没走到占用检查，
        // 因此这条桩用 lenient，避免被判成 UnnecessaryStubbing。
        lenient().when(taskMapper.selectByTaskIdIncludingDeleted(anyString(), any())).thenReturn(null);
    }

    private void stubEmptyCopy() {
        when(businessStoreService.copyTaskParamsInto(anyString(), anyString(), any()))
                .thenReturn(new BusinessStoreService.TaskParamsCopy(paramRow(7), "task"));
        when(sectionMapper.copySectionsFromTask(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(0);
        when(sectionMapper.selectDistinctRealBankIdsByTask(anyString(), any())).thenReturn(List.of());
    }

    private boolean hasWarning(TaskCopyResponse response, String code) {
        return response.warnings().stream().map(TaskCopyResponse.Warning::code).anyMatch(code::equals);
    }
}
