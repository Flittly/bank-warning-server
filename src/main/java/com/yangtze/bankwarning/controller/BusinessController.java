package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.dto.BankPayload;
import com.yangtze.bankwarning.dto.BanksCreateRequest;
import com.yangtze.bankwarning.dto.BasicParamPayload;
import com.yangtze.bankwarning.dto.BasicParamsCreateRequest;
import com.yangtze.bankwarning.dto.SectionPayload;
import com.yangtze.bankwarning.dto.SectionsCreateRequest;
import com.yangtze.bankwarning.dto.TaskPayload;
import com.yangtze.bankwarning.dto.TasksCreateRequest;
import com.yangtze.bankwarning.dto.TaskStatusUpdateRequest;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v0/bank")
public class BusinessController {

    private final BusinessStoreService businessStoreService;
    private final TaskExecutionService taskExecutionService;
    private final SectionValidationService sectionValidationService;

    public BusinessController(BusinessStoreService businessStoreService,
                              TaskExecutionService taskExecutionService,
                              SectionValidationService sectionValidationService) {
        this.businessStoreService = businessStoreService;
        this.taskExecutionService = taskExecutionService;
        this.sectionValidationService = sectionValidationService;
    }

    @PostMapping("/banks")
    public Map<String, Object> createBanks(@Valid @RequestBody BanksCreateRequest request) {
        boolean overwrite = Boolean.TRUE.equals(request.overwrite());
        List<Map<String, Object>> inserted = request.banks().stream()
                .map(bank -> businessStoreService.saveBank(bank, overwrite))
                .map(bank -> Map.of("id", bank.get("id"), "bank_id", bank.get("bank_id"), "bank_name", bank.get("bank_name")))
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
        List<Map<String, Object>> inserted = request.tasks().stream()
                .map(task -> businessStoreService.saveTask(task, overwrite))
                .map(task -> Map.of("id", task.get("id"), "task_id", task.get("task_id"), "task_name", task.get("task_name")))
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
        List<Map<String, Object>> inserted = request.params().stream()
                .map(param -> businessStoreService.saveBasicParam(param, overwrite))
                .map(param -> Map.of("id", param.get("id"), "param_id", param.get("param_id"), "param_name", param.get("param_name")))
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

    @PostMapping("/sections")
    public Map<String, Object> createSections(@Valid @RequestBody SectionsCreateRequest request) {
        boolean overwrite = Boolean.TRUE.equals(request.overwrite());
        boolean inherit = !Boolean.FALSE.equals(request.inheritFromBasicParam());
        List<Map<String, Object>> inserted = request.sections().stream()
                .map(section -> businessStoreService.saveSection(request.taskId(), section, inherit, overwrite))
                .map(section -> Map.of("id", section.get("id"), "section_id", section.get("section_id"), "section_name", section.get("section_name"), "bank_id", section.get("bank_id")))
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

    @PostMapping("/tasks/{task_id}/run/async")
    public Map<String, Object> runTaskAsync(@PathVariable("task_id") String taskId) {
        return taskExecutionService.submitTaskRun(taskId);
    }

    @PostMapping("/sections/{section_id}/validate")
    public Map<String, Object> validateSection(@PathVariable("section_id") String sectionId) {
        Map<String, Object> section = businessStoreService.getSection(sectionId);
        Map<String, Object> sectionGeometry = (Map<String, Object>) section.get("section_geometry");
        String benchId = (String) section.get("bench_id");

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
        Map<String, Object> section = businessStoreService.getSection(sectionId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("section_id", sectionId);
        result.put("is_valid", section.get("is_valid"));
        result.put("validation_status", section.get("validation_status"));
        result.put("validation_message", section.get("validation_message"));
        return result;
    }
}
