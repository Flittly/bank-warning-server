package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.ai.service.ReportPackageService;
import com.yangtze.bankwarning.ai.service.RiskSegmentSummaryService;
import com.yangtze.bankwarning.domain.dto.BankPayload;
import com.yangtze.bankwarning.domain.dto.BanksCreateRequest;
import com.yangtze.bankwarning.domain.dto.BasicParamPayload;
import com.yangtze.bankwarning.domain.dto.BasicParamsCreateRequest;
import com.yangtze.bankwarning.domain.dto.RiskResultOverrideRequest;
import com.yangtze.bankwarning.domain.dto.SectionPayload;
import com.yangtze.bankwarning.domain.dto.SectionsCreateRequest;
import com.yangtze.bankwarning.domain.dto.SectionBatchUpdateRequest;
import com.yangtze.bankwarning.domain.dto.TaskBanksAppendRequest;
import com.yangtze.bankwarning.domain.dto.TaskCopyRequest;
import com.yangtze.bankwarning.domain.dto.TaskCopyResponse;
import com.yangtze.bankwarning.domain.dto.TaskParamsUpdateRequest;
import com.yangtze.bankwarning.domain.dto.TasksCreateRequest;
import com.yangtze.bankwarning.domain.dto.TaskStatusUpdateRequest;
import com.yangtze.bankwarning.mapper.HydroConditionMapper;
import com.yangtze.bankwarning.service.BusinessStoreService;
import com.yangtze.bankwarning.service.SectionValidationService;
import com.yangtze.bankwarning.service.TaskCopyService;
import com.yangtze.bankwarning.service.TaskExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/v0/bank")
public class BusinessController {

    private final BusinessStoreService businessStoreService;
    private final TaskExecutionService taskExecutionService;
    private final SectionValidationService sectionValidationService;
    private final HydroConditionMapper hydroConditionMapper;
    private final RiskSegmentSummaryService riskSegmentSummaryService;
    private final TaskCopyService taskCopyService;

    public BusinessController(BusinessStoreService businessStoreService,
                              TaskExecutionService taskExecutionService,
                              SectionValidationService sectionValidationService,
                              HydroConditionMapper hydroConditionMapper,
                              RiskSegmentSummaryService riskSegmentSummaryService,
                              TaskCopyService taskCopyService) {
        this.businessStoreService = businessStoreService;
        this.taskExecutionService = taskExecutionService;
        this.sectionValidationService = sectionValidationService;
        this.hydroConditionMapper = hydroConditionMapper;
        this.riskSegmentSummaryService = riskSegmentSummaryService;
        this.taskCopyService = taskCopyService;
    }

    /**
     * 下拉里允许出现的水文条件白名单，元素格式 `water_qs|tidal_level`。
     *
     * <p>2026-10-20 收敛：只保留 84000 m³/s · 1998-2023 一项，其余历史组合
     * （10000／13000／16500／…／104000，以及 84000 的 1998-2018 与 dc/xc/zc）不再出现在下拉里。
     *
     * <p><b>只过滤下拉，没有删库。</b>`hydrodynamic_points`（每个组合 ~8~12 万条流场点）与
     * `basic_params` 的原始记录必须保留：已绑定的模板/断面要靠它回显「（当前）」，
     * 流场回放也要按组合 id 找工况目录。删数据会连带弄丢这两样。
     *
     * <p>要放开某个组合，往集合里加一项即可；要恢复全部，把 {@link #isHydroConditionVisible} 直接返回 true。
     */
    private static final Set<String> VISIBLE_HYDRO_CONDITIONS = Set.of("84000|1998-2023");

    /** 组合是否允许出现在下拉里。规范化为 `water_qs|tidal_level` 后比对，避免空格/大小写差异漏判。 */
    private static boolean isHydroConditionVisible(Map<String, Object> condition) {
        String key = column(condition, "water_qs") + "|" + column(condition, "tidal_level");
        return VISIBLE_HYDRO_CONDITIONS.contains(key);
    }

    /**
     * 取结果集里的列值。MyBatis 的 `resultType="map"` 不保证键名形态
     * （PostgreSQL 给 `water_qs`，开了 mapUnderscoreToCamelCase 可能是 `waterQs`），
     * 所以按「去掉下划线 + 忽略大小写」比对，取不到返回空串而不是 null。
     */
    private static String column(Map<String, Object> row, String name) {
        String wanted = name.replace("_", "").toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.replace("_", "").toLowerCase(Locale.ROOT).equals(wanted)) {
                continue;
            }
            return entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
        }
        return "";
    }

    @PostMapping("/banks")
    public Map<String, Object> createBanks(@Valid @RequestBody BanksCreateRequest request) {
        boolean overwrite = Boolean.TRUE.equals(request.overwrite());
        var inserted = request.banks().stream()
                .map(bank -> businessStoreService.saveBank(bank, overwrite))
                .map(b -> Map.of("id", b.id(), "bank_id", b.bankId(), "bank_name", b.bankName()))
                .toList();
        return Map.of("success", true, "inserted_count", inserted.size(), "banks", inserted);
    }

    @GetMapping("/banks")
    public Map<String, Object> listBanks(@RequestParam(name = "region_code", required = false) String regionCode) {
        return Map.of("success", true, "banks", businessStoreService.listBanks(regionCode));
    }

    @GetMapping("/banks/{bank_id}")
    public Map<String, Object> getBank(@PathVariable("bank_id") String bankId) {
        return Map.of("success", true, "bank", businessStoreService.getBank(bankId));
    }

    @PutMapping("/banks/{bank_id}")
    public Map<String, Object> updateBank(@PathVariable("bank_id") String bankId, @RequestBody BankPayload payload) {
        businessStoreService.updateBank(bankId, payload);
        return Map.of("success", true, "bank_id", bankId, "updated", true);
    }

    @DeleteMapping("/banks/{bank_id}")
    public Map<String, Object> deleteBank(@PathVariable("bank_id") String bankId) {
        businessStoreService.deleteBank(bankId);
        return Map.of("success", true, "bank_id", bankId, "deleted", true);
    }

    @PostMapping("/tasks")
    public Map<String, Object> createTasks(@Valid @RequestBody TasksCreateRequest request) {
        boolean overwrite = Boolean.TRUE.equals(request.overwrite());
        var inserted = request.tasks().stream()
                .map(task -> businessStoreService.saveTask(task, overwrite))
                .map(t -> Map.of("id", t.id(), "task_id", t.taskId(), "task_name", t.taskName()))
                .toList();
        return Map.of("success", true, "inserted_count", inserted.size(), "tasks", inserted);
    }

    @GetMapping("/tasks")
    public Map<String, Object> listTasks() {
        return Map.of("success", true, "tasks", businessStoreService.listTasks());
    }

    /**
     * 每个任务的有效结果条数：`{ counts: { task_id: n } }`。
     *
     * <p>只服务任务列表的一个前置判断 ——「该任务有没有结果」决定右键菜单里的「分析」
     * 能不能点。路径刻意用固定字面量 `result-counts` 而不是给 `/tasks/{task_id}` 加参数：
     * Spring 会优先匹配字面量段，不会被 `{task_id}` 抢走，也不需要额外的查询参数。
     */
    @GetMapping("/tasks/result-counts")
    public Map<String, Object> listTaskResultCounts() {
        return Map.of("success", true, "counts", businessStoreService.countResultsByTask());
    }

    @GetMapping("/tasks/{task_id}")
    public Map<String, Object> getTask(@PathVariable("task_id") String taskId) {
        return Map.of("success", true, "task", businessStoreService.getTask(taskId));
    }

    @DeleteMapping("/tasks/{task_id}")
    public Map<String, Object> deleteTask(@PathVariable("task_id") String taskId) {
        businessStoreService.deleteTask(taskId);
        return Map.of("success", true, "message", "Task deleted successfully");
    }

    /**
     * 复制任务：把「配置 + 断面几何 + 参数集」搬成一个**独立的新任务**。
     *
     * <p>默认只复制配置，不复制计算结果（{@code include_results=true} 才连结果与剖面一起搬）。
     * 三个 id 全部重生成、状态重置为 {@code pending}；岸段/DEM/水动力数据/模型缓存都按引用保留。
     *
     * <p>请求体可省略（等同默认策略）。{@code target_task_id} 用于幂等重试；
     * {@code target_user_id} 是 P1 的跨用户能力，当前只接受本人。
     *
     * <p>响应里的 {@code warnings} 必须原样展示：源任务在跑、参数是现物化的、
     * 某些岸段已失效 —— 这些不阻断复制，但用户不看就不知道副本为什么不完整。
     */
    @PostMapping("/tasks/{task_id}/copy")
    public TaskCopyResponse copyTask(@PathVariable("task_id") String taskId,
                                     @RequestBody(required = false) TaskCopyRequest request) {
        return taskCopyService.copy(taskId, request);
    }

    /**
     * 给已有任务补充（或整体替换）岸段关联。
     *
     * <p>这是「往现有任务里补岸段」的唯一后端入口。不新建任务、不动断面、不清结果 ——
     * 断面由前端随后按 {@code POST /v0/bank/sections} 单独落库，两步分开：
     * 关联这一步幂等，重试不会产生重复断面。
     *
     * <p>请求体 {@code {"bank_ids": ["b1","b2"], "mode": "merge"}}；{@code mode} 缺省为 merge。
     * 返回体里 {@code added} 是本次真正新增的（已存在的不重复计），
     * {@code missing} 非空表示有岸段在库里查不到，此时**未做任何修改**。
     */
    @PostMapping("/tasks/{task_id}/banks")
    public Map<String, Object> appendTaskBanks(
            @PathVariable("task_id") String taskId,
            @RequestBody TaskBanksAppendRequest request) {
        return businessStoreService.appendTaskBanks(taskId, request.bankIds(), request.mode());
    }

    /**
     * 用「该任务断面里实际用到的真实岸段」补全 {@code tasks.bank_ids}（幂等、只增不减）。
     *
     * <p>给历史数据兜底：早年的「补充岸段」在写关联前就失败过，断面已落库、bank_ids 没写，
     * 于是资源树（只读 bank_ids）与地图（读并集）会给出两个不同的岸段数。
     * 前端在加载任务时发现"有断面但没登记"的岸段就调它修一次，之后两边自然一致。
     */
    @PostMapping("/tasks/{task_id}/banks/sync")
    public Map<String, Object> syncTaskBanks(@PathVariable("task_id") String taskId) {
        return businessStoreService.syncTaskBanksFromSections(taskId);
    }

    @PutMapping("/tasks/{task_id}/status")
    public Map<String, Object> updateTaskStatus(@PathVariable("task_id") String taskId, @RequestBody TaskStatusUpdateRequest request) {
        businessStoreService.updateTaskStatus(taskId, request.status(), request.runStartedAt(), request.runCompletedAt(), request.errorMessage());
        return Map.of("success", true, "task_id", taskId, "updated", true);
    }

    /**
     * 执行任务下的断面。
     *
     * <p>{@code only_pending=true} 只算「还没有有效结果」的断面，已有结果不动 ——
     * 给任务补充岸段之后只想算新增的那几条时用它。缺省（不传）保持历史行为：
     * 先作废本任务全部结果再全量重算。
     *
     * <p>请求体可选 {@code {"section_ids": [...]}}：把增量范围再收窄到这一批断面
     * （补岸段只算本次新增，不把任务里其他"还没算过"的老断面一起拉进来）。
     */
    @PostMapping("/tasks/{task_id}/run")
    public Map<String, Object> runTask(
            @PathVariable("task_id") String taskId,
            @RequestParam(name = "only_pending", required = false) Boolean onlyPending,
            @RequestBody(required = false) Map<String, Object> body) {
        List<String> sectionIds = null;
        Object raw = body == null ? null : body.get("section_ids");
        if (raw instanceof List<?> list) {
            sectionIds = list.stream()
                    .filter(Objects::nonNull)
                    .map(v -> String.valueOf(v).trim())
                    .filter(v -> !v.isEmpty())
                    .toList();
        }
        return taskExecutionService.runTask(taskId, Boolean.TRUE.equals(onlyPending), sectionIds);
    }

    /**
     * 终止正在运行的任务。返回体里的 {@code mode} 是给前端区分文案用的：
     * {@code cooperative}（真在跑，已请求停止）/ {@code stale}（进程已死的僵尸，已直接标记）/
     * {@code not-running}（本来就没在跑）。
     */
    @PostMapping("/tasks/{task_id}/cancel")
    public Map<String, Object> cancelTask(@PathVariable("task_id") String taskId) {
        return taskExecutionService.cancelTask(taskId);
    }

    @GetMapping("/tasks/{task_id}/full")
    public Map<String, Object> getTaskFull(@PathVariable("task_id") String taskId) {
        return Map.of("success", true, "task_id", taskId, "data", businessStoreService.getTaskFullData(taskId));
    }

    @DeleteMapping("/tasks/{task_id}/clear")
    public Map<String, Object> clearTask(@PathVariable("task_id") String taskId) {
        return Map.of("success", true, "task_id", taskId, "deleted", businessStoreService.clearTaskData(taskId));
    }

    @PostMapping("/basic-params")
    public Map<String, Object> createBasicParams(@Valid @RequestBody BasicParamsCreateRequest request) {
        boolean overwrite = Boolean.TRUE.equals(request.overwrite());
        var inserted = request.params().stream()
                .map(param -> businessStoreService.saveBasicParam(param, overwrite))
                .map(param -> Map.of("id", param.id(), "param_id", param.paramId(), "param_name", param.paramName()))
                .toList();
        return Map.of("success", true, "inserted_count", inserted.size(), "params", inserted);
    }

    @GetMapping("/basic-params")
    public Map<String, Object> listBasicParams() {
        return Map.of("success", true, "params", businessStoreService.listBasicParams());
    }

    @GetMapping("/basic-params/{param_id}")
    public Map<String, Object> getBasicParam(@PathVariable("param_id") String paramId) {
        return Map.of("success", true, "param", businessStoreService.getBasicParam(paramId));
    }

    @PutMapping("/basic-params/{param_id}")
    public Map<String, Object> updateBasicParam(@PathVariable("param_id") String paramId, @RequestBody BasicParamPayload payload) {
        businessStoreService.updateBasicParam(paramId, payload);
        return Map.of("success", true, "param_id", paramId, "updated", true);
    }

    /**
     * 取「本任务自己的参数集」。没有就按来源物化一份（模板 → 断面快照 → 默认模板）。
     *
     * 任务的参数是**独立行**（basic_params.task_id 非空），不再是「引用模板库」：
     * 改模板库不影响它，改它也不影响别的任务。
     */
    @GetMapping("/tasks/{task_id}/params")
    public Map<String, Object> getTaskParams(
            @PathVariable("task_id") String taskId,
            @RequestParam(name = "template_param_id", required = false) String templateParamId) {
        return businessStoreService.getTaskParams(taskId, templateParamId);
    }

    /** 保存本任务的参数集：只写本任务那一行，并同步本任务的断面快照。 */
    @PutMapping("/tasks/{task_id}/params")
    public Map<String, Object> updateTaskParams(
            @PathVariable("task_id") String taskId,
            @RequestBody TaskParamsUpdateRequest request) {
        return businessStoreService.updateTaskParams(taskId, request);
    }

    /** 从模板库显式拷一份进本任务（复制后无关联，之后互不影响）。 */
    @PostMapping("/tasks/{task_id}/params/from-template")
    public Map<String, Object> importTaskParamsFromTemplate(
            @PathVariable("task_id") String taskId,
            @RequestParam("template_param_id") String templateParamId) {
        return businessStoreService.importTaskParamsFromTemplate(taskId, templateParamId);
    }

    @GetMapping("/hydro-conditions")
    public Map<String, Object> listHydroConditions() {
        // 库里取全部组合，再按白名单收口：模板库与「断面参数」两个弹窗共用这个接口，
        // 收口放在这里才能保证两处口径一致（前端各写一份过滤必然漂移）。
        var conditions = hydroConditionMapper.selectDistinctConditions().stream()
                .filter(BusinessController::isHydroConditionVisible)
                .toList();
        return Map.of("success", true, "conditions", conditions);
    }

    @PostMapping("/sections")
    public Map<String, Object> createSections(@Valid @RequestBody SectionsCreateRequest request) {
        boolean overwrite = Boolean.TRUE.equals(request.overwrite());
        boolean inherit = !Boolean.FALSE.equals(request.inheritFromBasicParam());
        var inserted = request.sections().stream()
                .map(section -> businessStoreService.saveSection(request.taskId(), section, inherit, overwrite))
                .map(section -> Map.of("id", section.id(), "section_id", section.sectionId(), "section_name", section.sectionName(), "bank_id", section.bankId()))
                .toList();
        return Map.of("success", true, "task_id", request.taskId(), "inserted_count", inserted.size(), "sections", inserted);
    }

    @GetMapping("/sections")
    public Map<String, Object> listSections(
            @RequestParam(name = "task_id", required = false) String taskId,
            @RequestParam(name = "bank_id", required = false) String bankId) {
        return Map.of("success", true, "task_id", taskId, "sections", businessStoreService.listSections(taskId, bankId));
    }

    @GetMapping("/sections/{section_id}")
    public Map<String, Object> getSection(@PathVariable("section_id") String sectionId) {
        return Map.of("success", true, "section", businessStoreService.getSection(sectionId));
    }

    @PutMapping("/sections/{section_id}")
    public Map<String, Object> updateSection(@PathVariable("section_id") String sectionId, @RequestBody SectionPayload payload) {
        BusinessStoreService.SectionGeometryUpdate update = businessStoreService.updateSection(sectionId, payload);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("section_id", sectionId);
        response.put("updated", true);
        // 几何变了 ⇒ 这条断面的旧结论已经被作废（见 BusinessStoreService#updateSection）。
        // had_result 是给前端的信号：true = 刚才作废过一份真结果，应当立刻
        // POST /sections/{id}/recompute 把它补回来；false = 本来就没算过，等任务整体运行即可。
        response.put("geometry_changed", update.geometryChanged());
        response.put("had_result", update.hadResult());
        return response;
    }

    /**
     * 单断面重算：只算这一条并覆盖它的结论（旧行已在保存几何时作废）。
     *
     * <p>失败以 {@code status:"failed" + error_code} 返回（HTTP 200）：几何已经存下来了，
     * 「这条算不出来」是数据问题，不该让前端当成请求异常。
     * 409 = 此刻不能算（任务在跑 / 该断面已在算），见 SectionRecomputeException。
     */
    @PostMapping("/sections/{section_id}/recompute")
    public Map<String, Object> recomputeSection(@PathVariable("section_id") String sectionId) {
        return taskExecutionService.recomputeSection(sectionId);
    }

    @PutMapping("/sections/{section_id}/reverse")
    public Map<String, Object> reverseSection(@PathVariable("section_id") String sectionId) {
        businessStoreService.reverseSection(sectionId);
        return Map.of("success", true, "section_id", sectionId, "reversed", true);
    }

    @PutMapping("/sections/batch-params")
    public Map<String, Object> batchUpdateSectionParams(@RequestBody SectionBatchUpdateRequest request) {
        businessStoreService.batchUpdateSectionParams(request.sectionIds(), request.params());
        return Map.of("success", true, "updated", request.sectionIds().size());
    }

    /**
     * 只读：该任务下有多少断面的参数快照与本任务的参数集不一致。
     * 作用域是任务自己的参数集，与模板库无关。
     */
    @GetMapping("/tasks/{task_id}/params/diff")
    public Map<String, Object> countParamDiffsForTask(@PathVariable("task_id") String taskId) {
        return Map.of(
                "success", true,
                "task_id", taskId,
                "pending", businessStoreService.countParamDiffsForTask(taskId)
        );
    }

    /**
     * 把本任务的断面参数快照同步为**本任务参数集**的当前值（幂等）。
     *
     * 路径名沿用历史命名（refresh-from-template），但语义已不再涉及模板库：
     * 任务参数是独立的一行，这个动作等价于「保存任务参数」里的断面同步部分，
     * 提供给需要单独补一次同步的脚本使用。已产出结果的任务同步后需要重跑。
     */
    @PutMapping("/tasks/{task_id}/params/refresh-from-template")
    public Map<String, Object> refreshParamsForTask(@PathVariable("task_id") String taskId) {
        return businessStoreService.refreshParamsForTask(taskId);
    }

    @DeleteMapping("/sections/{section_id}")
    public Map<String, Object> deleteSection(@PathVariable("section_id") String sectionId) {
        businessStoreService.deleteSection(sectionId);
        return Map.of("success", true, "section_id", sectionId, "deleted", true);
    }

    @GetMapping("/results")
    public Map<String, Object> listResults(
            @RequestParam(name = "task_id", required = false) String taskId,
            @RequestParam(name = "bank_id", required = false) String bankId,
            @RequestParam(name = "region_code", required = false) String regionCode) {
        return Map.of("success", true, "results", businessStoreService.listRiskResults(taskId, bankId, regionCode));
    }

    /**
     * 导出「风险段汇总」CSV：连续同类风险段的位置、长度（km）与占比。
     *
     * <p>与结果页「导出断面详情（整表 CSV）」配套使用 —— 明细回答「每条断面几级」，
     * 汇总回答「哪一段高风险、有多长、占多少」。计算口径完全在后端
     * {@link RiskSegmentSummaryService} 里，前端不再重算，避免两套口径漂移。
     *
     * @param taskId 任务 ID
     * @return UTF-8 CSV（带 BOM，Excel 直接双击不乱码）
     */
    @GetMapping("/risk-segments/export")
    public ResponseEntity<byte[]> exportRiskSegments(@RequestParam("task_id") String taskId) {
        String csv = riskSegmentSummaryService.renderCsv(taskId);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        String fileName = "风险段汇总_" + taskId + "_"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ReportPackageService.contentDisposition(fileName))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @GetMapping("/results/{section_id}")
    public Map<String, Object> getResult(@PathVariable("section_id") String sectionId) {
        // 断面在、结果没有 ⇒ 200 + status=not_computed，而不是 404：
        // 「还没算」是正常状态，用 404 表达会被前端渲染成一条 HTTP 错误。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.putAll(businessStoreService.getRiskResultPayload(sectionId));
        return body;
    }

    @GetMapping("/results/{section_id}/matrix")
    public Map<String, Object> getRiskMatrix(@PathVariable("section_id") String sectionId) {
        var result = businessStoreService.getRiskResultBySectionId(sectionId);
        return Map.of(
                "success", true,
                "section_id", sectionId,
                "matrix", result.indicators()
        );
    }

    /**
     * 人工修改某断面的风险结果（不重跑模型）。
     * patch 由前端按 riskLevel.py 的同一套规则重算后带上；服务端只校验不变量。
     */
    @PutMapping("/results/{section_id}/override")
    public Map<String, Object> overrideRiskResult(@PathVariable("section_id") String sectionId,
                                                  @RequestBody RiskResultOverrideRequest request) {
        var result = businessStoreService.overrideRiskResult(sectionId, request);
        return Map.of("success", true, "edited", true, "result", result);
    }

    /**
     * 恢复模型原始结果（驳回人工修改）。
     *
     * <p>若这一行本来就是人工**新建**的（该断面原本一条结果都没有），撤销 = 删掉它，
     * 此时 {@code result} 为 {@code null}、{@code status=not_computed} ——
     * 前端据此把断面打回「无数据」而不是保留一份假的模型值。
     * 这里不能继续用 {@code Map.of}：它不接受 null 值。
     */
    @DeleteMapping("/results/{section_id}/override")
    public Map<String, Object> revertRiskResultOverride(
            @PathVariable("section_id") String sectionId,
            @RequestParam(name = "task_id", required = false) String taskId) {
        var result = businessStoreService.revertRiskResultOverride(sectionId, taskId);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("success", true);
        payload.put("edited", false);
        payload.put("status", result == null ? "not_computed" : "computed");
        payload.put("result", result);
        return payload;
    }

    @GetMapping("/tasks/{task_id}/section-profiles")
    public Map<String, Object> listSectionProfiles(@PathVariable("task_id") String taskId) {
        return Map.of("success", true, "task_id", taskId, "profiles", businessStoreService.listSectionProfiles(taskId));
    }

    @GetMapping("/sections/{section_id}/profile")
    public Map<String, Object> getSectionProfile(@PathVariable("section_id") String sectionId) {
        return Map.of("success", true, "profile", businessStoreService.getSectionProfile(sectionId));
    }

    @PostMapping("/tasks/{task_id}/run/async")
    public Map<String, Object> runTaskAsync(@PathVariable("task_id") String taskId) {
        return taskExecutionService.submitTaskRun(taskId);
    }

    /**
     * 校验单个断面。
     *
     * <p>请求体可选：`{"disabled_rules": ["R-SPACE-01"]}`。不传 = 全部规则启用，
     * 与改造前的行为完全一致（老前端/脚本继续可用）。
     *
     * <p>被停用的规则由 {@link SectionValidationService} 跳过并记入 `skipped_rules`；
     * 跳过不是"放行错误数据"——问题会在模型执行阶段以更具体的原因暴露。
     */
    @PostMapping("/sections/{section_id}/validate")
    public Map<String, Object> validateSection(
            @PathVariable("section_id") String sectionId,
            @RequestBody(required = false) Map<String, Object> body) {
        var section = businessStoreService.getSection(sectionId);
        Map<String, Object> sectionGeometry = (Map<String, Object>) section.sectionGeometry();
        String benchId = section.benchId();

        SectionValidationService.ValidationResponse response =
                sectionValidationService.validateSection(sectionId, sectionGeometry, benchId, readDisabledRules(body));

        Map<String, Object> result = new HashMap<>();
        result.put("success", response.isValid() == null || response.isValid());
        result.put("section_id", sectionId);
        result.put("is_valid", response.isValid());
        result.put("validation_status", response.status());
        result.put("validation_message", response.message());
        result.put("skipped_rules", response.skippedRules());
        return result;
    }

    /**
     * 当前后端实现的校验规则清单。
     *
     * <p>前端 `rules/catalog.ts` 启动时可以用它做一次对账：前端有、后端没有 ⇒ 该规则行标"未实现"；
     * 后端有、前端没登记 ⇒ 控制台 warn。这是防"加了开关但后端不认"的最低成本手段。
     */
    @GetMapping("/validation-rules")
    public Map<String, Object> listValidationRules() {
        return Map.of("success", true, "rules", sectionValidationService.describeRules());
    }

    /**
     * 从请求体读 `disabled_rules`。
     *
     * <p>任何形状不合法（不是数组、元素不是字符串…）都当作"没有停用规则"，
     * 绝不让它把校验打成 400 —— 一个过期的客户端不该让整批断面校验全部失败。
     */
    private Set<String> readDisabledRules(Map<String, Object> body) {
        if (body == null) return Set.of();
        Object raw = body.get("disabled_rules");
        if (raw == null) raw = body.get("disabledRules");
        if (!(raw instanceof Iterable<?> items)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object item : items) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }

    @GetMapping("/sections/{section_id}/validation")
    public Map<String, Object> getSectionValidation(@PathVariable("section_id") String sectionId) {
        var section = businessStoreService.getSection(sectionId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("section_id", sectionId);
        result.put("is_valid", section.isValid());
        result.put("validation_status", section.validationStatus());
        result.put("validation_message", section.validationMessage());
        return result;
    }
}

