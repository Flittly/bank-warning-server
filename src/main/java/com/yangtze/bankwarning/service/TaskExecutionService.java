package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.config.TerrainMappingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.yangtze.bankwarning.dto.kafka.ModelTask;
import com.yangtze.bankwarning.kafka.ModelTaskProducer;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final BusinessStoreService businessStoreService;
    private final ModelGatewayService modelGatewayService;
    private final ModelTaskProducer modelTaskProducer;
    private final TaskRunStateService taskRunStateService;
    private final TerrainMappingProperties terrainMappingProperties;
    private final SectionProfileService sectionProfileService;

    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public TaskExecutionService(
            BusinessStoreService businessStoreService,
            ModelGatewayService modelGatewayService,
            ModelTaskProducer modelTaskProducer,
            TaskRunStateService taskRunStateService,
            TerrainMappingProperties terrainMappingProperties,
            SectionProfileService sectionProfileService) {
        this.businessStoreService = businessStoreService;
        this.modelGatewayService = modelGatewayService;
        this.modelTaskProducer = modelTaskProducer;
        this.taskRunStateService = taskRunStateService;
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

            for (Map<String, Object> section : sections) {
                String sectionId = String.valueOf(section.get("section_id"));
                log.info("[task-run] processing section, taskId={}, taskCode={}, sectionId={}, sectionName={}",
                        taskId, taskCode, sectionId, section.get("section_name"));
                Map<String, Object> payload = buildRiskLevelPayload(section);
                log.info("[task-run] built model payload, taskId={}, sectionId={}, payloadKeys={}", taskId, sectionId, payload.keySet());
                Map<String, Object> rawResult = modelGatewayService.runLegacyModelAndWait(
                        "/v0/mi/risk-level",
                        payload,
                        null);

                log.info("[task-run] model returned, taskId={}, sectionId={}, resultKeys={}", taskId, sectionId, rawResult.keySet());
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
            }

            businessStoreService.markTaskCompleted(taskId);
            log.info("[task-run] task completed, taskId={}, taskCode={}, resultCount={}", taskId, taskCode, results.size());
            return Map.of(
                    "success", true,
                    "task_id", taskId,
                    "status", "completed",
                    "results", results);
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
            String runId = taskRunStateService.createRun(taskId, sections.size());

            // 5. 遍历断面，发送到 Kafka
            for (Map<String, Object> section : sections) {
                String sectionId = String.valueOf(section.get("section_id"));
                String bankId = String.valueOf(section.get("bank_id"));
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
                modelTaskProducer.send(modelTask);
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
}
