package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.*;
import com.yangtze.bankwarning.domain.dto.*;
import com.yangtze.bankwarning.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
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

    public BankResponse saveBank(BankPayload payload, boolean overwrite) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (overwrite) {
            BankPO existing = bankMapper.selectByBankId(payload.bankId(), userId);
            if (existing != null) {
                BankPO po = toBankPO(payload);
                bankMapper.update(po);
                return BankResponse.from(bankMapper.selectByBankId(payload.bankId(), userId));
            }
        }
        BankPO po = toBankPO(payload);
        po.setUserId(SecurityUtils.getCurrentUserId());
        bankMapper.insert(po);
        return BankResponse.from(bankMapper.selectByBankId(payload.bankId(), userId));
    }

    public List<BankResponse> listBanks(String regionCode) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return bankMapper.selectAll(regionCode, userId).stream().map(BankResponse::from).collect(Collectors.toList());
    }

    public Map<String, Object> getBank(String bankId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        BankPO po = bankMapper.selectByBankId(bankId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
        return toMap(po);
    }

    public void updateBank(String bankId, BankPayload payload) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        getBank(bankId);
        BankPO po = toBankPO(payload);
        po.setBankId(bankId);
        bankMapper.update(po);
    }

    public void deleteBank(String bankId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (bankMapper.deleteByBankId(bankId, userId) == 0) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
    }

    // ==================== Task ====================

    public TaskResponse saveTask(TaskPayload payload, boolean overwrite) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (overwrite) {
            TaskPO existing = taskMapper.selectByTaskId(payload.taskId(), userId);
            if (existing != null) {
                TaskPO po = toTaskPO(payload);
                taskMapper.update(po);
                return TaskResponse.from(taskMapper.selectByTaskId(payload.taskId(), userId));
            }
        }
        TaskPO po = toTaskPO(payload);
        po.setUserId(SecurityUtils.getCurrentUserId());
        taskMapper.insert(po);
        return TaskResponse.from(taskMapper.selectByTaskId(payload.taskId(), userId));
    }

    public List<TaskResponse> listTasks() {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return taskMapper.selectAll(userId).stream().map(TaskResponse::from).collect(Collectors.toList());
    }

    public TaskResponse getTask(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        TaskPO po = taskMapper.selectByTaskId(taskId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return TaskResponse.from(po);
    }

    public void updateTaskStatus(String taskId, String status, String runStartedAt, String runCompletedAt, String errorMessage) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        getTask(taskId);
        taskMapper.updateStatus(taskId, status, runStartedAt, runCompletedAt, errorMessage, userId);
    }

    public void markTaskRunning(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markRunning(taskId, userId);
    }

    public void markTaskCompleted(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markCompleted(taskId, userId);
    }

    public void markTaskError(String taskId, String errorMessage) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markError(taskId, errorMessage, userId);
    }

    public void markTaskPartialFailed(String taskId, String errorMessage) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markPartialFailed(taskId, errorMessage, userId);
    }

    public void deleteTask(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (taskMapper.deleteByTaskId(taskId, userId) == 0) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
    }

    // ==================== BasicParam ====================

    public BasicParamResponse saveBasicParam(BasicParamPayload payload, boolean overwrite) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (overwrite) {
            BasicParamPO existing = basicParamMapper.selectByParamId(payload.paramId(), userId);
            if (existing != null) {
                BasicParamPO po = toBasicParamPO(payload);
                basicParamMapper.update(po);
                return BasicParamResponse.from(basicParamMapper.selectByParamId(payload.paramId(), userId));
            }
        }
        BasicParamPO po = toBasicParamPO(payload);
        po.setUserId(SecurityUtils.getCurrentUserId());
        basicParamMapper.insert(po);
        return BasicParamResponse.from(basicParamMapper.selectByParamId(payload.paramId(), userId));
    }

    public List<BasicParamResponse> listBasicParams() {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return basicParamMapper.selectAll(userId).stream().map(BasicParamResponse::from).collect(Collectors.toList());
    }

    public BasicParamResponse getBasicParam(String paramId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        BasicParamPO po = basicParamMapper.selectByParamId(paramId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Basic parameter not found: " + paramId);
        }
        return BasicParamResponse.from(po);
    }

    public void updateBasicParam(String paramId, BasicParamPayload payload) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        getBasicParam(paramId);
        BasicParamPO po = toBasicParamPO(payload);
        po.setParamId(paramId);
        basicParamMapper.update(po);
    }

    // ==================== Section ====================

    public SectionResponse saveSection(String taskId, SectionPayload payload, boolean inheritFromBasicParam, boolean overwrite) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (overwrite && sectionMapper.existsBySectionId(payload.sectionId(), userId)) {
            updateSection(payload.sectionId(), payload);
            return getSection(payload.sectionId());
        }

        String taskCode = getTaskCode(taskId);
        Integer basicParamId = payload.basicParamId();
        Map<String, Object> baseParams = inheritFromBasicParam && basicParamId != null
                ? toMap(basicParamMapper.selectById(basicParamId, userId))
                : new LinkedHashMap<>();
        Map<String, Object> merged = mergeSectionParams(baseParams, payload);
        SectionPO po = toSectionPO(taskCode, basicParamId, merged, payload);
        po.setUserId(SecurityUtils.getCurrentUserId());
        sectionMapper.insert(po);
            return SectionResponse.from(sectionMapper.selectBySectionId(payload.sectionId(), userId));
    }

    public List<SectionResponse> listSections(String taskId, String bankId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        String taskCode = taskId == null ? null : getTaskCode(taskId);
        return sectionMapper.selectByTaskIdAndBankId(taskCode, bankId, userId).stream().map(SectionResponse::from).collect(Collectors.toList());
    }

    public SectionResponse getSection(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        SectionPO po = sectionMapper.selectBySectionId(sectionId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
        return SectionResponse.from(po);
    }

    public void updateSection(String sectionId, SectionPayload payload) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        getSection(sectionId);
        SectionPO po = toSectionPO(null, null, null, payload);
        po.setSectionId(sectionId);
        sectionMapper.update(po);
    }

    public void deleteSection(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (sectionMapper.deleteBySectionId(sectionId, userId) == 0) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
    }

    public Map<String, Object> getTaskFullData(String taskId) {
        return Map.of(
                "task", getTask(taskId),
                "sections", listSections(taskId, null));
    }

    public Map<String, Integer> clearTaskData(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        String taskCode = getTaskCode(taskId);
        int resultCount = riskResultMapper.countByTaskId(taskCode, userId);
        int sectionCount = sectionMapper.countByTaskId(taskCode, userId);
        riskResultMapper.deleteByTaskId(taskCode, userId);
        sectionMapper.deleteByTaskId(taskCode, userId);
        return Map.of("sections", sectionCount, "results", resultCount);
    }

    public void clearTaskResults(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        String taskCode = getTaskCode(taskId);
        riskResultMapper.deleteByTaskId(taskCode, userId);
        sectionProfileMapper.deleteByTaskId(taskCode, userId);
    }

    public void batchUpdateSectionParams(List<String> sectionIds, Map<String, Object> params) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (sectionIds == null || sectionIds.isEmpty()) return;
        sectionMapper.batchUpdateParams(sectionIds, params, userId);
    }

    // ==================== RiskResult ====================

    public List<RiskResultResponse> listRiskResults(String taskId, String bankId, String regionCode) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        String taskCode = taskId == null ? null : getTaskCode(taskId);
        return riskResultMapper.selectByTaskIdAndBankIdAndRegionCode(taskCode, bankId, regionCode, userId)
                .stream().map(RiskResultResponse::from).collect(Collectors.toList());
    }

    public RiskResultResponse getRiskResultBySectionId(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        RiskResultPO po = riskResultMapper.selectLatestBySectionId(sectionId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Risk result not found for section_id: " + sectionId);
        }
        return RiskResultResponse.from(po);
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
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        RiskResultPO po = new RiskResultPO();
        po.setUserId(SecurityUtils.getCurrentUserId());
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
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (riskResultMapper.existsByRunIdAndSectionId(runId, sectionId, userId)) {
            return;
        }
        Map<String, Object> section = getSectionForResult(sectionId);
        RiskResultPO po = new RiskResultPO();
        po.setUserId(SecurityUtils.getCurrentUserId());
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

    public List<SectionProfileResponse> listSectionProfiles(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return sectionProfileMapper.selectByTaskId(getTaskCode(taskId), userId)
                .stream().map(SectionProfileResponse::from).collect(Collectors.toList());
    }

    public SectionProfileResponse getSectionProfile(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        SectionProfilePO po = sectionProfileMapper.selectLatestBySectionId(sectionId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Section profile not found for section_id: " + sectionId);
        }
        return SectionProfileResponse.from(po);
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
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        SectionProfilePO po = new SectionProfilePO();
        po.setUserId(SecurityUtils.getCurrentUserId());
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
        TaskResponse t = getTask(taskId);
        return t == null || t.id() == null ? null : t.id().intValue();
    }

    public String getTaskCode(String taskId) {
        TaskResponse t = getTask(taskId);
        return t == null ? null : t.taskId();
    }

    public Integer getSectionDbId(String sectionId) {
        return toInteger(getSectionForResult(sectionId).get("id"));
    }

    public List<Map<String, Object>> getSectionsByTask(String taskId) {
        return sectionMapper.selectByTaskIdAndBankId(getTaskCode(taskId), null, SecurityUtils.getCurrentUserIdForDataFilter())
                .stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getSectionForResult(String sectionId) {
        return toMap(sectionMapper.selectBySectionId(sectionId, SecurityUtils.getCurrentUserIdForDataFilter()));
    }

    // ==================== PO <-> Map Conversion (仅内部使用，公共 API 已改用 DTO) ====================

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

    // 由 getSectionForResult / saveSection 内部分支使用
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

    // 由 saveSection 的参数继承分支使用
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
        po.setHs(merged != null ? toDouble(merged.get("hs")) : payload.hs());
        po.setHc(merged != null ? toDouble(merged.get("hc")) : payload.hc());
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

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
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
