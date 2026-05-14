package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.*;
import com.yangtze.bankwarning.dto.BankPayload;
import com.yangtze.bankwarning.dto.BasicParamPayload;
import com.yangtze.bankwarning.dto.SectionPayload;
import com.yangtze.bankwarning.dto.TaskPayload;
import com.yangtze.bankwarning.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BusinessStoreService {

    private final BankMapper bankMapper;
    private final TaskMapper taskMapper;
    private final BasicParamMapper basicParamMapper;
    private final SectionMapper sectionMapper;
    private final RiskResultMapper riskResultMapper;
    private final SectionProfileMapper sectionProfileMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BusinessStoreService(
            BankMapper bankMapper,
            TaskMapper taskMapper,
            BasicParamMapper basicParamMapper,
            SectionMapper sectionMapper,
            RiskResultMapper riskResultMapper,
            SectionProfileMapper sectionProfileMapper) {
        this.bankMapper = bankMapper;
        this.taskMapper = taskMapper;
        this.basicParamMapper = basicParamMapper;
        this.sectionMapper = sectionMapper;
        this.riskResultMapper = riskResultMapper;
        this.sectionProfileMapper = sectionProfileMapper;
    }

    // ==================== Bank ====================

    public Map<String, Object> saveBank(BankPayload payload, boolean overwrite) {
        if (overwrite) {
            BankPO existing = bankMapper.selectByBankId(payload.bankId());
            if (existing != null) {
                BankPO po = toBankPO(payload);
                bankMapper.update(po);
                return toMap(bankMapper.selectByBankId(payload.bankId()));
            }
        }
        BankPO po = toBankPO(payload);
        bankMapper.insert(po);
        return toMap(bankMapper.selectByBankId(payload.bankId()));
    }

    public List<Map<String, Object>> listBanks(String regionCode) {
        return bankMapper.selectAll(regionCode).stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getBank(String bankId) {
        BankPO po = bankMapper.selectByBankId(bankId);
        if (po == null) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
        return toMap(po);
    }

    public void updateBank(String bankId, BankPayload payload) {
        getBank(bankId);
        BankPO po = toBankPO(payload);
        po.setBankId(bankId);
        bankMapper.update(po);
    }

    public void deleteBank(String bankId) {
        if (bankMapper.deleteByBankId(bankId) == 0) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
    }

    // ==================== Task ====================

    public Map<String, Object> saveTask(TaskPayload payload, boolean overwrite) {
        if (overwrite) {
            TaskPO existing = taskMapper.selectByTaskId(payload.taskId());
            if (existing != null) {
                TaskPO po = toTaskPO(payload);
                taskMapper.update(po);
                return toMap(taskMapper.selectByTaskId(payload.taskId()));
            }
        }
        TaskPO po = toTaskPO(payload);
        taskMapper.insert(po);
        return toMap(taskMapper.selectByTaskId(payload.taskId()));
    }

    public List<Map<String, Object>> listTasks() {
        return taskMapper.selectAll().stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getTask(String taskId) {
        TaskPO po = taskMapper.selectByTaskId(taskId);
        if (po == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return toMap(po);
    }

    public void updateTaskStatus(String taskId, String status, String runStartedAt, String runCompletedAt, String errorMessage) {
        getTask(taskId);
        taskMapper.updateStatus(taskId, status, runStartedAt, runCompletedAt, errorMessage);
    }

    public void markTaskRunning(String taskId) {
        taskMapper.markRunning(taskId);
    }

    public void markTaskCompleted(String taskId) {
        taskMapper.markCompleted(taskId);
    }

    public void markTaskError(String taskId, String errorMessage) {
        taskMapper.markError(taskId, errorMessage);
    }

    public void markTaskPartialFailed(String taskId, String errorMessage) {
        taskMapper.markPartialFailed(taskId, errorMessage);
    }

    public void deleteTask(String taskId) {
        if (taskMapper.deleteByTaskId(taskId) == 0) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
    }

    // ==================== BasicParam ====================

    public Map<String, Object> saveBasicParam(BasicParamPayload payload, boolean overwrite) {
        if (overwrite) {
            BasicParamPO existing = basicParamMapper.selectByParamId(payload.paramId());
            if (existing != null) {
                BasicParamPO po = toBasicParamPO(payload);
                basicParamMapper.update(po);
                return toMap(basicParamMapper.selectByParamId(payload.paramId()));
            }
        }
        BasicParamPO po = toBasicParamPO(payload);
        basicParamMapper.insert(po);
        return toMap(basicParamMapper.selectByParamId(payload.paramId()));
    }

    public List<Map<String, Object>> listBasicParams() {
        return basicParamMapper.selectAll().stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getBasicParam(String paramId) {
        BasicParamPO po = basicParamMapper.selectByParamId(paramId);
        if (po == null) {
            throw new IllegalArgumentException("Basic parameter not found: " + paramId);
        }
        return toMap(po);
    }

    public void updateBasicParam(String paramId, BasicParamPayload payload) {
        getBasicParam(paramId);
        BasicParamPO po = toBasicParamPO(payload);
        po.setParamId(paramId);
        basicParamMapper.update(po);
    }

    // ==================== Section ====================

    public Map<String, Object> saveSection(String taskId, SectionPayload payload, boolean inheritFromBasicParam, boolean overwrite) {
        if (overwrite && sectionMapper.existsBySectionId(payload.sectionId())) {
            updateSection(payload.sectionId(), payload);
            return getSection(payload.sectionId());
        }

        String taskCode = getTaskCode(taskId);
        Integer basicParamId = payload.basicParamId();
        Map<String, Object> baseParams = inheritFromBasicParam && basicParamId != null
                ? toMap(basicParamMapper.selectById(basicParamId))
                : new LinkedHashMap<>();
        Map<String, Object> merged = mergeSectionParams(baseParams, payload);
        SectionPO po = toSectionPO(taskCode, basicParamId, merged, payload);
        sectionMapper.insert(po);
        return toMap(sectionMapper.selectBySectionId(payload.sectionId()));
    }

    public List<Map<String, Object>> listSections(String taskId, String bankId) {
        String taskCode = taskId == null ? null : getTaskCode(taskId);
        return sectionMapper.selectByTaskIdAndBankId(taskCode, bankId).stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getSection(String sectionId) {
        SectionPO po = sectionMapper.selectBySectionId(sectionId);
        if (po == null) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
        return toMap(po);
    }

    public void updateSection(String sectionId, SectionPayload payload) {
        getSection(sectionId);
        SectionPO po = toSectionPO(null, null, null, payload);
        po.setSectionId(sectionId);
        sectionMapper.update(po);
    }

    public void deleteSection(String sectionId) {
        if (sectionMapper.deleteBySectionId(sectionId) == 0) {
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
        int resultCount = riskResultMapper.countByTaskId(taskCode);
        int sectionCount = sectionMapper.countByTaskId(taskCode);
        riskResultMapper.deleteByTaskId(taskCode);
        sectionMapper.deleteByTaskId(taskCode);
        return Map.of("sections", sectionCount, "results", resultCount);
    }

    public void clearTaskResults(String taskId) {
        String taskCode = getTaskCode(taskId);
        riskResultMapper.deleteByTaskId(taskCode);
        sectionProfileMapper.deleteByTaskId(taskCode);
    }

    // ==================== RiskResult ====================

    public List<Map<String, Object>> listRiskResults(String taskId, String bankId, String regionCode) {
        String taskCode = taskId == null ? null : getTaskCode(taskId);
        return riskResultMapper.selectByTaskIdAndBankIdAndRegionCode(taskCode, bankId, regionCode)
                .stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getRiskResultBySectionId(String sectionId) {
        RiskResultPO po = riskResultMapper.selectLatestBySectionId(sectionId);
        if (po == null) {
            throw new IllegalArgumentException("Risk result not found for section_id: " + sectionId);
        }
        return toMap(po);
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
        RiskResultPO po = new RiskResultPO();
        po.setTaskId(taskId);
        po.setSectionId(sectionId);
        po.setSectionName(sectionName);
        po.setRegionCode(regionCode);
        po.setBankId(bankId);
        po.setRiskLevel(riskLevel);
        po.setIndicators(writeJson(indicators));
        riskResultMapper.insert(po);
    }

    public void saveRiskResultIfAbsent(
            String runId,
            String taskId,
            String sectionId,
            Integer riskLevel,
            Map<String, Object> indicators) {
        if (riskResultMapper.existsByRunIdAndSectionId(runId, sectionId)) {
            return;
        }
        Map<String, Object> section = getSectionForResult(sectionId);
        RiskResultPO po = new RiskResultPO();
        po.setRunId(runId);
        po.setTaskId(taskId);
        po.setSectionId(sectionId);
        po.setSectionName(String.valueOf(section.get("section_name")));
        po.setRegionCode(String.valueOf(section.get("region_code")));
        po.setBankId(String.valueOf(section.get("bank_id")));
        po.setRiskLevel(riskLevel);
        po.setIndicators(writeJson(indicators));
        riskResultMapper.insert(po);
    }

    // ==================== SectionProfile ====================

    public List<Map<String, Object>> listSectionProfiles(String taskId) {
        return sectionProfileMapper.selectByTaskId(getTaskCode(taskId))
                .stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getSectionProfile(String sectionId) {
        SectionProfilePO po = sectionProfileMapper.selectLatestBySectionId(sectionId);
        if (po == null) {
            throw new IllegalArgumentException("Section profile not found for section_id: " + sectionId);
        }
        return toMap(po);
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
        SectionProfilePO po = new SectionProfilePO();
        po.setTaskId(taskId);
        po.setSectionId(sectionId);
        po.setSectionName(sectionName);
        po.setRegionCode(regionCode);
        po.setBankId(bankId);
        po.setDemId(demId);
        po.setSourceCaseId(sourceCaseId);
        po.setInterval(interval != null ? interval.doubleValue() : null);
        po.setDeepestIndex(deepestIndex);
        po.setSlopeFootIndex(slopeFootIndex);
        po.setPointCount(pointCount);
        po.setProfileData(writeJson(profileData));
        sectionProfileMapper.insertOrUpdate(po);
    }

    // ==================== Helper Methods ====================

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

    public Map<String, Object> getSectionForResult(String sectionId) {
        return getSection(sectionId);
    }

    // ==================== PO <-> Map Conversion ====================

    private Map<String, Object> toMap(TaskPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("task_id", po.getTaskId());
        map.put("task_name", po.getTaskName());
        map.put("bank_ids", parseJson(po.getBankIds()));
        map.put("description", po.getDescription());
        map.put("status", po.getStatus());
        map.put("run_started_at", po.getRunStartedAt());
        map.put("run_completed_at", po.getRunCompletedAt());
        map.put("error_message", po.getErrorMessage());
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toMap(BankPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("bank_id", po.getBankId());
        map.put("bank_name", po.getBankName());
        map.put("region_code", po.getRegionCode());
        map.put("bank_geometry", parseJson(po.getBankGeometry()));
        map.put("reversed", po.getReversed());
        map.put("description", po.getDescription());
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toMap(SectionPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("task_id", po.getTaskId());
        map.put("section_id", po.getSectionId());
        map.put("section_name", po.getSectionName());
        map.put("bank_id", po.getBankId());
        map.put("region_code", po.getRegionCode());
        map.put("segment_index", po.getSegmentIndex());
        map.put("section_geometry", parseJson(po.getSectionGeometry()));
        map.put("vertical_foot_point", parseJson(po.getVerticalFootPoint()));
        map.put("distance", po.getDistance());
        map.put("basic_param_id", po.getBasicParamId());
        map.put("param_name", po.getParamName());
        map.put("segment", po.getSegment());
        map.put("current_timepoint", po.getCurrentTimepoint());
        map.put("set_name", po.getSetName());
        map.put("water_qs", po.getWaterQs());
        map.put("tidal_level", po.getTidalLevel());
        map.put("bench_id", po.getBenchId());
        map.put("ref_id", po.getRefId());
        map.put("hs", po.getHs());
        map.put("hc", po.getHc());
        map.put("protection_level", po.getProtectionLevel());
        map.put("control_level", po.getControlLevel());
        map.put("comparison_timepoint", po.getComparisonTimepoint());
        map.put("risk_thresholds", parseJson(po.getRiskThresholds()));
        map.put("weights", parseJson(po.getWeights()));
        map.put("other_params", parseJson(po.getOtherParams()));
        map.put("is_valid", po.getIsValid());
        map.put("validation_status", po.getValidationStatus());
        map.put("validation_message", po.getValidationMessage());
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toMap(RiskResultPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("run_id", po.getRunId());
        map.put("task_id", po.getTaskId());
        map.put("section_id", po.getSectionId());
        map.put("section_name", po.getSectionName());
        map.put("region_code", po.getRegionCode());
        map.put("bank_id", po.getBankId());
        map.put("risk_level", po.getRiskLevel());
        map.put("indicators", parseJson(po.getIndicators()));
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toMap(SectionProfilePO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("task_id", po.getTaskId());
        map.put("section_id", po.getSectionId());
        map.put("section_name", po.getSectionName());
        map.put("region_code", po.getRegionCode());
        map.put("bank_id", po.getBankId());
        map.put("dem_id", po.getDemId());
        map.put("source_case_id", po.getSourceCaseId());
        map.put("interval", po.getInterval());
        map.put("deepest_index", po.getDeepestIndex());
        map.put("slope_foot_index", po.getSlopeFootIndex());
        map.put("point_count", po.getPointCount());
        map.put("profile_data", parseJson(po.getProfileData()));
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toMap(BasicParamPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("param_id", po.getParamId());
        map.put("param_name", po.getParamName());
        map.put("segment", po.getSegment());
        map.put("current_timepoint", po.getCurrentTimepoint());
        map.put("set_name", po.getSetName());
        map.put("water_qs", po.getWaterQs());
        map.put("tidal_level", po.getTidalLevel());
        map.put("bench_id", po.getBenchId());
        map.put("ref_id", po.getRefId());
        map.put("hs", po.getHs());
        map.put("hc", po.getHc());
        map.put("protection_level", po.getProtectionLevel());
        map.put("control_level", po.getControlLevel());
        map.put("comparison_timepoint", po.getComparisonTimepoint());
        map.put("risk_thresholds", parseJson(po.getRiskThresholds()));
        map.put("weights", parseJson(po.getWeights()));
        map.put("other_params", parseJson(po.getOtherParams()));
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    // ==================== Payload -> PO Conversion ====================

    private BankPO toBankPO(BankPayload payload) {
        BankPO po = new BankPO();
        po.setBankId(payload.bankId());
        po.setBankName(payload.bankName());
        po.setRegionCode(payload.regionCode());
        po.setBankGeometry(writeJson(payload.bankGeometry()));
        po.setDescription(payload.description());
        po.setReversed(payload.reversed() != null && payload.reversed());
        return po;
    }

    private TaskPO toTaskPO(TaskPayload payload) {
        TaskPO po = new TaskPO();
        po.setTaskId(payload.taskId());
        po.setTaskName(payload.taskName());
        po.setBankIds(writeJson(payload.bankIds()));
        po.setDescription(payload.description());
        return po;
    }

    private BasicParamPO toBasicParamPO(BasicParamPayload payload) {
        BasicParamPO po = new BasicParamPO();
        po.setParamId(payload.paramId());
        po.setParamName(payload.paramName());
        po.setSegment(payload.segment());
        po.setCurrentTimepoint(payload.currentTimepoint());
        po.setSetName(payload.setName());
        po.setWaterQs(payload.waterQs());
        po.setTidalLevel(payload.tidalLevel());
        po.setBenchId(payload.benchId());
        po.setRefId(payload.refId());
        po.setHs(payload.hs());
        po.setHc(payload.hc());
        po.setProtectionLevel(payload.protectionLevel());
        po.setControlLevel(payload.controlLevel());
        po.setComparisonTimepoint(payload.comparisonTimepoint());
        po.setRiskThresholds(writeJson(payload.riskThresholds()));
        po.setWeights(writeJson(payload.weights()));
        po.setOtherParams(writeJson(payload.otherParams()));
        return po;
    }

    private SectionPO toSectionPO(String taskCode, Integer basicParamId, Map<String, Object> merged, SectionPayload payload) {
        SectionPO po = new SectionPO();
        po.setTaskId(taskCode);
        po.setSectionId(payload.sectionId());
        po.setSectionName(payload.sectionName());
        po.setBankId(payload.bankId());
        po.setRegionCode(payload.regionCode());
        po.setSegmentIndex(payload.segmentIndex());
        po.setSectionGeometry(writeJson(payload.sectionGeometry()));
        po.setVerticalFootPoint(writeJson(merged != null ? merged.get("vertical_foot_point") : payload.verticalFootPoint()));
        po.setDistance(payload.distance());
        po.setBasicParamId(basicParamId);
        po.setParamName(merged != null ? (String) merged.get("param_name") : payload.paramName());
        po.setSegment(merged != null ? (String) merged.get("segment") : payload.segment());
        po.setCurrentTimepoint(merged != null ? (String) merged.get("current_timepoint") : payload.currentTimepoint());
        po.setSetName(merged != null ? (String) merged.get("set_name") : payload.setName());
        po.setWaterQs(merged != null ? (String) merged.get("water_qs") : payload.waterQs());
        po.setTidalLevel(merged != null ? (String) merged.get("tidal_level") : payload.tidalLevel());
        po.setBenchId(merged != null ? (String) merged.get("bench_id") : payload.benchId());
        po.setRefId(merged != null ? (String) merged.get("ref_id") : payload.refId());
        po.setHs(payload.hs());
        po.setHc(payload.hc());
        po.setProtectionLevel(merged != null ? (String) merged.get("protection_level") : payload.protectionLevel());
        po.setControlLevel(merged != null ? (String) merged.get("control_level") : payload.controlLevel());
        po.setComparisonTimepoint(merged != null ? (String) merged.get("comparison_timepoint") : payload.comparisonTimepoint());
        po.setRiskThresholds(writeJson(merged != null ? merged.get("risk_thresholds") : payload.riskThresholds()));
        po.setWeights(writeJson(merged != null ? merged.get("weights") : payload.weights()));
        po.setOtherParams(writeJson(merged != null ? merged.get("other_params") : payload.otherParams()));
        return po;
    }

    // ==================== Utility Methods ====================

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

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write JSON", e);
        }
    }

    private Object parseJson(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
