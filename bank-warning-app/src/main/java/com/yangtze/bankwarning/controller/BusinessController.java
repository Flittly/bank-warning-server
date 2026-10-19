package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.domain.dto.BankPayload;
import com.yangtze.bankwarning.domain.dto.BanksCreateRequest;
import com.yangtze.bankwarning.domain.dto.BasicParamPayload;
import com.yangtze.bankwarning.domain.dto.BasicParamsCreateRequest;
import com.yangtze.bankwarning.domain.dto.RiskResultOverrideRequest;
import com.yangtze.bankwarning.domain.dto.SectionPayload;
import com.yangtze.bankwarning.domain.dto.SectionsCreateRequest;
import com.yangtze.bankwarning.domain.dto.SectionBatchUpdateRequest;
import com.yangtze.bankwarning.domain.dto.TaskParamsUpdateRequest;
import com.yangtze.bankwarning.domain.dto.TasksCreateRequest;
import com.yangtze.bankwarning.domain.dto.TaskStatusUpdateRequest;
import com.yangtze.bankwarning.mapper.HydroConditionMapper;
import com.yangtze.bankwarning.service.BusinessStoreService;
import com.yangtze.bankwarning.service.SectionValidationService;
import com.yangtze.bankwarning.service.TaskExecutionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v0/bank")
public class BusinessController {

    private final BusinessStoreService businessStoreService;
    private final TaskExecutionService taskExecutionService;
    private final SectionValidationService sectionValidationService;
    private final HydroConditionMapper hydroConditionMapper;

    public BusinessController(BusinessStoreService businessStoreService,
                              TaskExecutionService taskExecutionService,
                              SectionValidationService sectionValidationService,
                              HydroConditionMapper hydroConditionMapper) {
        this.businessStoreService = businessStoreService;
        this.taskExecutionService = taskExecutionService;
        this.sectionValidationService = sectionValidationService;
        this.hydroConditionMapper = hydroConditionMapper;
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

    @GetMapping("/tasks/{task_id}")
    public Map<String, Object> getTask(@PathVariable("task_id") String taskId) {
        return Map.of("success", true, "task", businessStoreService.getTask(taskId));
    }

    @DeleteMapping("/tasks/{task_id}")
    public Map<String, Object> deleteTask(@PathVariable("task_id") String taskId) {
        businessStoreService.deleteTask(taskId);
        return Map.of("success", true, "message", "Task deleted successfully");
    }

    @PutMapping("/tasks/{task_id}/status")
    public Map<String, Object> updateTaskStatus(@PathVariable("task_id") String taskId, @RequestBody TaskStatusUpdateRequest request) {
        businessStoreService.updateTaskStatus(taskId, request.status(), request.runStartedAt(), request.runCompletedAt(), request.errorMessage());
        return Map.of("success", true, "task_id", taskId, "updated", true);
    }

    @PostMapping("/tasks/{task_id}/run")
    public Map<String, Object> runTask(@PathVariable("task_id") String taskId) {
        return taskExecutionService.runTask(taskId);
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
        return Map.of("success", true, "conditions", hydroConditionMapper.selectDistinctConditions());
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
        businessStoreService.updateSection(sectionId, payload);
        return Map.of("success", true, "section_id", sectionId, "updated", true);
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

    @GetMapping("/results/{section_id}")
    public Map<String, Object> getResult(@PathVariable("section_id") String sectionId) {
        return Map.of("success", true, "result", businessStoreService.getRiskResultBySectionId(sectionId));
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

    /** 恢复模型原始结果（驳回人工修改）。 */
    @DeleteMapping("/results/{section_id}/override")
    public Map<String, Object> revertRiskResultOverride(
            @PathVariable("section_id") String sectionId,
            @RequestParam(name = "task_id", required = false) String taskId) {
        var result = businessStoreService.revertRiskResultOverride(sectionId, taskId);
        return Map.of("success", true, "edited", false, "result", result);
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

    @PostMapping("/sections/{section_id}/validate")
    public Map<String, Object> validateSection(@PathVariable("section_id") String sectionId) {
        var section = businessStoreService.getSection(sectionId);
        Map<String, Object> sectionGeometry = (Map<String, Object>) section.sectionGeometry();
        String benchId = section.benchId();

        SectionValidationService.ValidationResponse response =
                sectionValidationService.validateSection(sectionId, sectionGeometry, benchId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", response.isValid() == null || response.isValid());
        result.put("section_id", sectionId);
        result.put("is_valid", response.isValid());
        result.put("validation_status", response.status());
        result.put("validation_message", response.message());
        return result;
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

