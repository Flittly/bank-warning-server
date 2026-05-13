package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskRunPO {
    private Long id;
    private String runId;
    private String taskId;
    private String status;
    private Integer expectedCount;
    private Integer completedCount;
    private Integer failedCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
