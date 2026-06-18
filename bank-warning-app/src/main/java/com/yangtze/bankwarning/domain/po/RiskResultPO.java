package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RiskResultPO {
    private Long id;
    private Long userId;
    private String runId;
    private String taskId;
    private String sectionId;
    private String sectionName;
    private String regionCode;
    private String bankId;
    private Integer riskLevel;
    private String indicators;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
