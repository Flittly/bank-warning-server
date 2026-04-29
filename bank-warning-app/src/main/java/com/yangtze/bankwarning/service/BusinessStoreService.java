package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.dto.BankPayload;
import com.yangtze.bankwarning.dto.BasicParamPayload;
import com.yangtze.bankwarning.dto.SectionPayload;
import com.yangtze.bankwarning.dto.TaskPayload;
import com.yangtze.bankwarning.repository.BankRepository;
import com.yangtze.bankwarning.repository.BasicParamRepository;
import com.yangtze.bankwarning.repository.RiskResultRepository;
import com.yangtze.bankwarning.repository.SectionRepository;
import com.yangtze.bankwarning.repository.SectionProfileRepository;
import com.yangtze.bankwarning.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BusinessStoreService {

    private final BankRepository bankRepository;
    private final TaskRepository taskRepository;
    private final BasicParamRepository basicParamRepository;
    private final SectionRepository sectionRepository;
    private final RiskResultRepository riskResultRepository;
    private final SectionProfileRepository sectionProfileRepository;

    public BusinessStoreService(
            BankRepository bankRepository,
            TaskRepository taskRepository,
            BasicParamRepository basicParamRepository,
            SectionRepository sectionRepository,
            RiskResultRepository riskResultRepository,
            SectionProfileRepository sectionProfileRepository) {
        this.bankRepository = bankRepository;
        this.taskRepository = taskRepository;
        this.basicParamRepository = basicParamRepository;
        this.sectionRepository = sectionRepository;
        this.riskResultRepository = riskResultRepository;
        this.sectionProfileRepository = sectionProfileRepository;
    }

    public Map<String, Object> saveBank(BankPayload payload, boolean overwrite) {
        return bankRepository.save(payload, overwrite);
    }

    public List<Map<String, Object>> listBanks(String regionCode) {
        return bankRepository.list(regionCode);
    }

    public Map<String, Object> getBank(String bankId) {
        Map<String, Object> row = bankRepository.getByBankId(bankId);
        if (row == null) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
        return row;
    }

    public void updateBank(String bankId, BankPayload payload) {
        getBank(bankId);
        bankRepository.update(bankId, payload);
    }

    public void deleteBank(String bankId) {
        if (bankRepository.deleteByBankId(bankId) == 0) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
    }

    public Map<String, Object> saveTask(TaskPayload payload, boolean overwrite) {
        return taskRepository.save(payload, overwrite);
    }

    public List<Map<String, Object>> listTasks() {
        return taskRepository.list();
    }

    public Map<String, Object> getTask(String taskId) {
        Map<String, Object> row = taskRepository.getByTaskId(taskId);
        if (row == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return row;
    }

    public void updateTaskStatus(String taskId, String status, String runStartedAt, String runCompletedAt, String errorMessage) {
        getTask(taskId);
        taskRepository.updateStatus(taskId, status, runStartedAt, runCompletedAt, errorMessage);
    }

    public void markTaskRunning(String taskId) {
        taskRepository.markRunning(taskId);
    }

    public void markTaskCompleted(String taskId) {
        taskRepository.markCompleted(taskId);
    }

    public void markTaskError(String taskId, String errorMessage) {
        taskRepository.markError(taskId, errorMessage);
    }

    public void markTaskPartialFailed(String taskId, String errorMessage) {
        taskRepository.markPartialFailed(taskId, errorMessage);
    }

    public void deleteTask(String taskId) {
        if (taskRepository.deleteByTaskId(taskId) == 0) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
    }

    public Map<String, Object> saveBasicParam(BasicParamPayload payload, boolean overwrite) {
        return basicParamRepository.save(payload, overwrite);
    }

    public List<Map<String, Object>> listBasicParams() {
        return basicParamRepository.list();
    }

    public Map<String, Object> getBasicParam(String paramId) {
        Map<String, Object> row = basicParamRepository.getByParamId(paramId);
        if (row == null) {
            throw new IllegalArgumentException("Basic parameter not found: " + paramId);
        }
        return row;
    }

    public void updateBasicParam(String paramId, BasicParamPayload payload) {
        getBasicParam(paramId);
        basicParamRepository.update(paramId, payload);
    }

    public Map<String, Object> saveSection(String taskId, SectionPayload payload, boolean inheritFromBasicParam, boolean overwrite) {
        if (overwrite && sectionRepository.existsBySectionId(payload.sectionId())) {
            updateSection(payload.sectionId(), payload);
            return getSection(payload.sectionId());
        }

        String taskCode = getTaskCode(taskId);
        Integer basicParamId = payload.basicParamId();
        Map<String, Object> baseParams = inheritFromBasicParam && basicParamId != null
                ? basicParamRepository.getById(basicParamId)
                : new LinkedHashMap<>();
        Map<String, Object> merged = mergeSectionParams(baseParams, payload);
        return sectionRepository.insert(taskCode, basicParamId, merged, payload);
    }

    public List<Map<String, Object>> listSections(String taskId, String bankId) {
        String taskCode = taskId == null ? null : getTaskCode(taskId);
        return sectionRepository.list(taskCode, bankId);
    }

    public Map<String, Object> getSection(String sectionId) {
        Map<String, Object> row = sectionRepository.getBySectionId(sectionId);
        if (row == null) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
        return row;
    }

    public void updateSection(String sectionId, SectionPayload payload) {
        getSection(sectionId);
        sectionRepository.update(sectionId, payload);
    }

    public void deleteSection(String sectionId) {
        if (sectionRepository.deleteBySectionId(sectionId) == 0) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
    }

    public Map<String, Object> getTaskFullData(String taskId) {
        return Map.of(
                "task", getTask(taskId),
                "sections", listSections(taskId, null));
    }

    public Map<String, Integer> clearTaskData(String taskId) {
        String taskCode = getTaskCode(taskId);
        int resultCount = riskResultRepository.countByTaskId(taskCode);
        int sectionCount = sectionRepository.countByTaskId(taskCode);
        riskResultRepository.deleteByTaskId(taskCode);
        sectionRepository.deleteByTaskId(taskCode);
        return Map.of("sections", sectionCount, "results", resultCount);
    }

    public void clearTaskResults(String taskId) {
        String taskCode = getTaskCode(taskId);
        riskResultRepository.deleteByTaskId(taskCode);
        sectionProfileRepository.deleteByTaskId(taskCode);
    }

    public List<Map<String, Object>> listRiskResults(String taskId, String bankId, String regionCode) {
        String taskCode = taskId == null ? null : getTaskCode(taskId);
        return riskResultRepository.list(taskCode, bankId, regionCode);
    }

    public Map<String, Object> getRiskResultBySectionId(String sectionId) {
        Map<String, Object> row = riskResultRepository.getLatestBySectionId(sectionId);
        if (row == null) {
            throw new IllegalArgumentException("Risk result not found for section_id: " + sectionId);
        }
        return row;
    }

    public List<Map<String, Object>> listSectionProfiles(String taskId) {
        return sectionProfileRepository.listByTaskId(getTaskCode(taskId));
    }

    public Map<String, Object> getSectionProfile(String sectionId) {
        Map<String, Object> row = sectionProfileRepository.getLatestBySectionId(sectionId);
        if (row == null) {
            throw new IllegalArgumentException("Section profile not found for section_id: " + sectionId);
        }
        return row;
    }

    public void saveSectionProfile(
            String taskId,
            String sectionId,
            String sectionName,
            String regionCode,
            String bankId,
            String demId,
            String sourceCaseId,
            Number interval,
            Integer deepestIndex,
            Integer slopeFootIndex,
            Integer pointCount,
            Map<String, Object> profileData,
            Map<String, Object> geometry) {
        sectionProfileRepository.save(
                taskId,
                sectionId,
                sectionName,
                regionCode,
                bankId,
                demId,
                sourceCaseId,
                interval,
                deepestIndex,
                slopeFootIndex,
                pointCount,
                profileData,
                geometry);
    }

    public Integer getTaskDbId(String taskId) {
        return toInteger(getTask(taskId).get("id"));
    }

    public String getTaskCode(String taskId) {
        return String.valueOf(getTask(taskId).get("task_id"));
    }

    public Integer getSectionDbId(String sectionId) {
        return toInteger(getSection(sectionId).get("id"));
    }

    public List<Map<String, Object>> getSectionsByTask(String taskId) {
        return listSections(taskId, null);
    }

    public void saveRiskResult(
            String taskId,
            String sectionId,
            String sectionName,
            String regionCode,
            String bankId,
            Integer riskLevel,
            Map<String, Object> indicators,
            Map<String, Object> geometry) {
        riskResultRepository.save(taskId, sectionId, sectionName, regionCode, bankId, riskLevel, indicators, geometry);
    }

    private Map<String, Object> mergeSectionParams(Map<String, Object> baseParams, SectionPayload payload) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseParams != null) {
            merged.putAll(baseParams);
        }
        putIfNotNull(merged, "segment_index", payload.segmentIndex());
        putIfNotNull(merged, "vertical_foot_point", payload.verticalFootPoint());
        putIfNotNull(merged, "distance", payload.distance());
        putIfNotNull(merged, "param_name", payload.paramName());
        putIfNotNull(merged, "segment", payload.segment());
        putIfNotNull(merged, "current_timepoint", payload.currentTimepoint());
        putIfNotNull(merged, "set_name", payload.setName());
        putIfNotNull(merged, "water_qs", payload.waterQs());
        putIfNotNull(merged, "tidal_level", payload.tidalLevel());
        putIfNotNull(merged, "bench_id", payload.benchId());
        putIfNotNull(merged, "ref_id", payload.refId());
        putIfNotNull(merged, "hs", payload.hs());
        putIfNotNull(merged, "hc", payload.hc());
        putIfNotNull(merged, "protection_level", payload.protectionLevel());
        putIfNotNull(merged, "control_level", payload.controlLevel());
        putIfNotNull(merged, "comparison_timepoint", payload.comparisonTimepoint());
        putIfNotNull(merged, "risk_thresholds", payload.riskThresholds());
        putIfNotNull(merged, "weights", payload.weights());
        putIfNotNull(merged, "other_params", payload.otherParams());
        return merged;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
    public Map<String, Object> getSectionForResult(String sectionId) {
        return getSection(sectionId);
    }

    // 在现有类中新增：幂等保存结果
    public void saveRiskResultIfAbsent(
            String runId,
            String taskId,
            String sectionId,
            Integer riskLevel,
            Map<String, Object> indicators) {
        // 先查这个 runId + sectionId 是否已存在
        if (riskResultRepository.existsByRunIdAndSectionId(runId, sectionId)) {
            // 已存在，直接返回不重复保存
            return;
        }
        // 不存在，查 section 真实信息并保存
        Map<String, Object> section = getSectionForResult(sectionId);
        riskResultRepository.saveWithRunId(
                runId,
                taskId,
                sectionId,
                String.valueOf(section.get("section_name")),
                String.valueOf(section.get("region_code")),
                String.valueOf(section.get("bank_id")),
                riskLevel,
                indicators,
                castMap(section.get("geometry"))
        );
    }

    // 辅助方法：转换为 Map
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }
}
