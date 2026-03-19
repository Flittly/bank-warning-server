package com.yangtze.bankwarning.dto.kafka;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

// 任务结果DTO，包含模型任务的所有必要信息，用于在Kafka中传输
@Data
public class ModelResult {
    private String runId;
    private String taskId;
    private String sectionId;
    private String status;
    private Integer riskLevel;
    private Map<String, Object> rawResult;
    private String artifactPath;
    private String workerId;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private String errorMessage;
}
