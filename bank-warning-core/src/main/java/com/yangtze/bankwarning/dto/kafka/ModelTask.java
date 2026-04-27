package com.yangtze.bankwarning.dto.kafka;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

// 任务消息DTO，包含模型任务的所有必要信息，用于在Kafka中传输
@Data
public class ModelTask {
    private String runId;
    private String taskId;
    private String sectionId;
    private String bankId;
    private String regionCode;
    private String modelType;
    private String terrainBucket;  // RustFS bucket 名称
    private String terrainKey;     // RustFS 地形文件路径
    private Map<String, Object> payload;
    private Instant submittedAt;
    private String traceId;
    private Integer retryCount;
}
