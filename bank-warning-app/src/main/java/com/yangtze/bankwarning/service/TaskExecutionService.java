package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.config.TerrainMappingProperties;
import com.yangtze.bankwarning.service.async.TaskDispatchPort;
import com.yangtze.bankwarning.service.async.TaskRunStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.yangtze.bankwarning.dto.kafka.ModelTask;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final BusinessStoreService businessStoreService;
    private final ModelGatewayService modelGatewayService;
    private final TaskDispatchPort taskDispatchPort;
    private final TaskRunStatePort taskRunStatePort;
    private final TerrainMappingProperties terrainMappingProperties;
    private final SectionProfileService sectionProfileService;

    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public TaskExecutionService(
            BusinessStoreService businessStoreService,
            ModelGatewayService modelGatewayService,
            TaskDispatchPort taskDispatchPort,
            TaskRunStatePort taskRunStatePort,
            TerrainMappingProperties terrainMappingProperties,
            SectionProfileService sectionProfileService) {
        this.businessStoreService = businessStoreService;
        this.modelGatewayService = modelGatewayService;
        this.taskDispatchPort = taskDispatchPort;
        this.taskRunStatePort = taskRunStatePort;
        this.terrainMappingProperties = terrainMappingProperties;
        this.sectionProfileService = sectionProfileService;
    }

    public Map<String, Object> runTask(String taskId) {
        log.info("[task-run] starting task run, taskId={}", taskId);
        businessStoreService.getTask(taskId);
        log.info("[task-run] task exists, clearing old results, taskId={}", taskId);
        businessStoreService.clearTaskResults(taskId);
        log.info("[task-run] old results cleared, marking running, taskId={}", taskId);
        businessStoreService.markTaskRunning(taskId);

        try {
            String taskCode = businessStoreService.getTaskCode(taskId);
            List<Map<String, Object>> sections = businessStoreService.getSectionsByTask(taskId);
            log.info("[task-run] loaded sections, taskId={}, taskCode={}, sectionCount={}", taskId, taskCode, sections.size());
            List<Map<String, Object>> results = new ArrayList<>();
            List<Map<String, Object>> failures = new ArrayList<>();

            for (Map<String, Object> section : sections) {
                String sectionId = String.valueOf(section.get("section_id"));
                try {
                    validateSectionGeometry(taskId, sectionId, section.get("section_geometry"));
                    log.info("[task-run] processing section, taskId={}, taskCode={}, sectionId={}, sectionName={}",
                            taskId, taskCode, sectionId, section.get("section_name"));
                    Map<String, Object> payload = buildRiskLevelPayload(section);
                    log.info("[task-run] built model payload, taskId={}, sectionId={}, payloadKeys={}", taskId, sectionId, payload.keySet());
                    Map<String, Object> rawResult = modelGatewayService.runLegacyModelAndWait(
                            "/v0/mi/risk-level",
                            payload,
                            null);

                    log.info("[task-run] model returned, taskId={}, sectionId={}, resultKeys={}", taskId, sectionId, rawResult.keySet());
                    rawResult.put("water_qs", section.get("water_qs"));
                    rawResult.put("tidal_level", section.get("tidal_level"));
                    Integer riskLevel = toRiskLevel(rawResult.get("risk-level"));
                    log.info("[task-run] parsed risk level, taskId={}, sectionId={}, riskLevel={}", taskId, sectionId, riskLevel);

                    sectionProfileService.saveForSection(taskCode, section);

                    businessStoreService.saveRiskResult(
                            taskCode,
                            sectionId,
                            String.valueOf(section.get("section_name")),
                            String.valueOf(section.get("region_code")),
                            String.valueOf(section.get("bank_id")),
                            riskLevel,
                            rawResult,
                            castMap(section.get("geometry")));
                    log.info("[task-run] saved risk result, taskId={}, taskCode={}, sectionId={}", taskId, taskCode, sectionId);

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("section_id", section.get("section_id"));
                    result.put("status", 200);
                    result.put("response", rawResult);
                    result.put("executed_at", Instant.now().toString());
                    results.add(result);
                } catch (Exception sectionException) {
                    log.error("[task-run] section failed, taskId={}, sectionId={}, message={}",
                            taskId,
                            sectionId,
                            sectionException.getMessage(),
                            sectionException);
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("section_id", section.get("section_id"));
                    failed.put("status", 500);
                    failed.put("error", sectionException.getMessage());
                    failed.put("executed_at", Instant.now().toString());
                    failures.add(failed);
                }
            }

            String finalStatus;
            if (failures.isEmpty()) {
                finalStatus = "completed";
                businessStoreService.markTaskCompleted(taskId);
            } else if (results.isEmpty()) {
                finalStatus = "error";
                businessStoreService.markTaskError(taskId, joinFailureMessages(failures));
            } else {
                finalStatus = "partial_failed";
                businessStoreService.markTaskPartialFailed(taskId, joinFailureMessages(failures));
            }

            log.info("[task-run] task finished, taskId={}, taskCode={}, successCount={}, failureCount={}, finalStatus={}",
                    taskId,
                    taskCode,
                    results.size(),
                    failures.size(),
                    finalStatus);
            return Map.of(
                    "success", !results.isEmpty(),
                    "task_id", taskId,
                    "status", finalStatus,
                    "results", results,
                    "failures", failures,
                    "summary", Map.of(
                            "total", sections.size(),
                            "success", results.size(),
                            "failed", failures.size()
                    ));
        } catch (Exception exception) {
            log.error("[task-run] task failed, taskId={}, message={}", taskId, exception.getMessage(), exception);
            businessStoreService.markTaskError(taskId, exception.getMessage());
            throw exception;
        }
    }

    private Map<String, Object> buildRiskLevelPayload(Map<String, Object> section) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("segment", section.get("segment"));
        payload.put("current-timepoint", section.get("current_timepoint"));
        payload.put("set", section.get("set_name"));
        payload.put("water-qs", section.get("water_qs"));
        payload.put("tidal-level", section.get("tidal_level"));
        payload.put("bench-id", section.get("bench_id"));
        payload.put("ref-id", section.get("ref_id"));
        payload.put("hs", section.get("hs"));
        payload.put("hc", section.get("hc"));
        payload.put("protection-level", section.get("protection_level"));
        payload.put("control-level", section.get("control_level"));
        payload.put("comparison-timepoint", section.get("comparison_timepoint"));
        payload.put("risk-thresholds", section.get("risk_thresholds") == null ? "NONE" : section.get("risk_thresholds"));
        payload.put("section-geometry", section.get("section_geometry"));

        Map<String, Object> weights = castMap(section.get("weights"));
        payload.put("wRE", weights.getOrDefault("wRE", "NONE"));
        payload.put("wNM", weights.getOrDefault("wNM", "NONE"));
        payload.put("wGE", weights.getOrDefault("wGE", "NONE"));
        payload.put("wRL", weights.getOrDefault("wRL", "NONE"));
        return payload;
    }

    private Integer toRiskLevel(Object riskLevelValue) {
        if (riskLevelValue instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                Object item = list.get(index);
                if (item instanceof Number number && number.intValue() == 1) {
                    return index + 1;
                }
            }
        }
        return 0;
    }

    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }
    public Map<String, Object> submitTaskRun(String taskId) {
        if (!kafkaEnabled) {
            throw new IllegalStateException("Kafka模式未启用，请设置环境变量 KAFKA_ENABLED=true");
        }

        log.info("[task-submit-kafka] 开始提交任务 taskId={}", taskId);

        // 1. 校验任务存在
        businessStoreService.getTask(taskId);
        log.info("[task-submit-kafka] 任务存在，清理旧结果 taskId={}", taskId);

        // 2. 清理本次运行旧结果
        businessStoreService.clearTaskResults(taskId);
        log.info("[task-submit-kafka] 旧结果已清理，标记任务运行中 taskId={}", taskId);

        // 3. 标记任务状态为 running
        businessStoreService.markTaskRunning(taskId);

        try {
            String taskCode = businessStoreService.getTaskCode(taskId);
            List<Map<String, Object>> sections = businessStoreService.getSectionsByTask(taskId);
            log.info("[task-submit-kafka] 加载断面完成 taskId={} taskCode={} sectionCount={}",
                    taskId, taskCode, sections.size());

            // 4. 生成 runId
            String runId = taskRunStatePort.createRun(taskId, sections.size());

            // 5. 遍历断面，发送到 Kafka
            for (Map<String, Object> section : sections) {
                String sectionId = String.valueOf(section.get("section_id"));
                String bankId = String.valueOf(section.get("bank_id"));
                validateSectionGeometry(taskId, sectionId, section.get("section_geometry"));
                log.info("[task-submit-kafka] 发送断面到 Kafka taskId={} sectionId={} bankId={}",
                        taskId, sectionId, bankId);

                // 从数据库获取岸段信息，读取 terrain_key
                Map<String, Object> bank = businessStoreService.getBank(bankId);
                String terrainKey = String.valueOf(bank.get("terrain_key"));
                String terrainBucket = terrainMappingProperties.getTerrainBucket();
                
                // 如果数据库中没有 terrain_key，使用默认格式
                if (terrainKey == null || "null".equals(terrainKey) || terrainKey.isBlank()) {
                    terrainKey = "tiff/" + bankId + ".tif";
                    log.info("[task-submit-kafka] 数据库无地形映射，使用默认格式: bankId={} -> terrainKey={}", bankId, terrainKey);
                } else {
                    log.info("[task-submit-kafka] 从数据库获取地形映射: bankId={} -> terrainKey={}", bankId, terrainKey);
                }

                // 构造任务消息
                Map<String, Object> payload = buildRiskLevelPayload(section);
                ModelTask modelTask = new ModelTask();
                modelTask.setRunId(runId);
                modelTask.setTaskId(taskId);
                modelTask.setSectionId(sectionId);
                modelTask.setBankId(bankId);
                modelTask.setRegionCode(String.valueOf(section.get("region_code")));
                modelTask.setModelType("risk-level");
                modelTask.setTerrainBucket(terrainBucket);  // 设置 RustFS bucket
                modelTask.setTerrainKey(terrainKey);         // 设置地形文件路径
                modelTask.setPayload(payload);
                modelTask.setSubmittedAt(Instant.now());
                modelTask.setTraceId(UUID.randomUUID().toString());
                modelTask.setRetryCount(0);

                // 发送到 Kafka（Key 为 bankId，保证同一岸段的任务由同一 Worker 处理）
                taskDispatchPort.send(modelTask);
            }

            log.info("[task-submit-kafka] 所有断面已发送到 Kafka taskId={} runId={}", taskId, runId);

            return Map.of(
                    "success", true,
                    "taskId", taskId,
                    "runId", runId,
                    "status", "SUBMITTED",
                    "expectedCount", sections.size());

        } catch (Exception exception) {
            log.error("[task-submit-kafka] 任务提交失败 taskId={} message={}",
                    taskId, exception.getMessage(), exception);
            businessStoreService.markTaskError(taskId, exception.getMessage());
            throw exception;
        }
    }

    private void validateSectionGeometry(String taskId, String sectionId, Object sectionGeometryObject) {
        if (!(sectionGeometryObject instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid section_geometry for taskId=%s sectionId=%s: payload is not an object",
                    taskId,
                    sectionId));
        }

        Map<String, Object> geometry = castMap(rawMap);
        Object type = geometry.get("type");
        if (!(type instanceof String typeValue) || !"LineString".equalsIgnoreCase(typeValue)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid section_geometry for taskId=%s sectionId=%s: type must be LineString",
                    taskId,
                    sectionId));
        }

        Object coordinatesObject = geometry.get("coordinates");
        if (!(coordinatesObject instanceof List<?> coordinates) || coordinates.size() < 2) {
            throw new IllegalArgumentException(String.format(
                    "Invalid section_geometry for taskId=%s sectionId=%s: coordinates must contain at least 2 points",
                    taskId,
                    sectionId));
        }

        if (!hasNonZeroSegment(taskId, sectionId, coordinates)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid section_geometry for taskId=%s sectionId=%s: all line segments are zero-length",
                    taskId,
                    sectionId));
        }
    }

    private boolean hasNonZeroSegment(String taskId, String sectionId, List<?> coordinates) {
        Double previousX = null;
        Double previousY = null;

        for (Object coordinate : coordinates) {
            if (!(coordinate instanceof List<?> point) || point.size() < 2) {
                throw new IllegalArgumentException(String.format(
                        "Invalid section_geometry for taskId=%s sectionId=%s: coordinate point must contain at least [x, y]",
                        taskId,
                        sectionId));
            }

            Double currentX = toDouble(point.get(0));
            Double currentY = toDouble(point.get(1));
            if (currentX == null || currentY == null) {
                throw new IllegalArgumentException(String.format(
                        "Invalid section_geometry for taskId=%s sectionId=%s: coordinate values must be numeric",
                        taskId,
                        sectionId));
            }

            if (previousX != null && previousY != null) {
                double deltaX = currentX - previousX;
                double deltaY = currentY - previousY;
                if ((deltaX * deltaX + deltaY * deltaY) > 0d) {
                    return true;
                }
            }

            previousX = currentX;
            previousY = currentY;
        }

        return false;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String joinFailureMessages(List<Map<String, Object>> failures) {
        if (failures.isEmpty()) {
            return null;
        }
        return failures.stream()
                .map(item -> String.format("section_id=%s error=%s", item.get("section_id"), item.get("error")))
                .collect(Collectors.joining(" | "));
    }
}
