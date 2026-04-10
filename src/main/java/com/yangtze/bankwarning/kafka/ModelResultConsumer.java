package com.yangtze.bankwarning.kafka;

import com.yangtze.bankwarning.dto.kafka.ModelResult;
import com.yangtze.bankwarning.service.BusinessStoreService;
import com.yangtze.bankwarning.service.SectionProfileService;
import com.yangtze.bankwarning.service.TaskRunStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

// Kafka 结果消费者：负责从 Kafka 消费模型计算结果
@Component
public class ModelResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(ModelResultConsumer.class);

    private final BusinessStoreService businessStoreService;
    private final TaskRunStateService taskRunStateService;
    private final SectionProfileService sectionProfileService;

    public ModelResultConsumer(
            BusinessStoreService businessStoreService,
            TaskRunStateService taskRunStateService,
            SectionProfileService sectionProfileService) {
        this.businessStoreService = businessStoreService;
        this.taskRunStateService = taskRunStateService;
        this.sectionProfileService = sectionProfileService;
    }

    // 监听结果 Topic
    @KafkaListener(
            topics = "${app.kafka.result-topic}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ModelResult result, Acknowledgment acknowledgment) {
        log.info("[kafka-result-consume] 收到结果 runId={} taskId={} sectionId={} status={}",
                result.getRunId(),
                result.getTaskId(),
                result.getSectionId(),
                result.getStatus());

        try {
            // 第一步：校验必填字段
            validate(result);

            // 第二步：根据状态处理
            if ("SUCCESS".equals(result.getStatus())) {
                // 成功：幂等保存结果
                businessStoreService.saveRiskResultIfAbsent(
                        result.getRunId(),
                        result.getTaskId(),
                        result.getSectionId(),
                        result.getRiskLevel(),
                        buildIndicators(result)
                );
                Map<String, Object> section = businessStoreService.getSectionForResult(result.getSectionId());
                sectionProfileService.saveForSection(result.getTaskId(), section);
                // 标记该断面成功
                taskRunStateService.markSectionSuccess(result.getRunId());
            } else {
                // 失败：只标记失败
                taskRunStateService.markSectionError(
                        result.getRunId(),
                        result.getErrorMessage()
                );
            }

            // 第三步：手动确认消费成功
            acknowledgment.acknowledge();

        } catch (Exception exception) {
            log.error("[kafka-result-consume] 处理失败 runId={} sectionId={} error={}",
                    result.getRunId(),
                    result.getSectionId(),
                    exception.getMessage(),
                    exception);
            // 抛出异常，让 Kafka 触发重试机制
            throw exception;
        }
    }

    // 校验必填字段
    private void validate(ModelResult result) {
        if (result.getRunId() == null || result.getRunId().isBlank()) {
            throw new IllegalArgumentException("缺少 runId");
        }
        if (result.getTaskId() == null || result.getTaskId().isBlank()) {
            throw new IllegalArgumentException("缺少 taskId");
        }
        if (result.getSectionId() == null || result.getSectionId().isBlank()) {
            throw new IllegalArgumentException("缺少 sectionId");
        }
        if (result.getStatus() == null || result.getStatus().isBlank()) {
            throw new IllegalArgumentException("缺少 status");
        }
    }

    // 构建指标信息
    private Map<String, Object> buildIndicators(ModelResult result) {
        return Map.of(
                "workerId", result.getWorkerId(),
                "rawResult", result.getRawResult(),
                "artifactPath", result.getArtifactPath(),
                "startedAt", result.getStartedAt(),
                "completedAt", result.getCompletedAt(),
                "durationMs", result.getDurationMs(),
                "errorMessage", result.getErrorMessage()
        );
    }
}
