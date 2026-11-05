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
    /** 复制来源：非空表示这行是 `POST /tasks/{id}/copy` 产生的副本。 */
    private String sourceTaskId;
    /** 复制发生时刻；与 {@code createdAt} 通常相同，单独留一列是为了区分「建任务」与「复制任务」。 */
    private LocalDateTime copiedAt;
}
