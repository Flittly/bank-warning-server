package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskPO {
    private Long id;
    private Long userId;
    private String taskId;
    private String taskName;
    private String bankIds;
    private String description;
    private String status;
    private LocalDateTime runStartedAt;
    private LocalDateTime runCompletedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
