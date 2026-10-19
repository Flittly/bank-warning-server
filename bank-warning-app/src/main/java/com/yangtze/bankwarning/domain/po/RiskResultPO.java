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
    /**
     * 人工修改留痕，NULL = 纯模型结果。
     * 结构见 {@code migration/V12__risk_result_manual_override.sql}：
     * original_indicators / original_risk_level / edited_at / edited_by / note
     */
    private String editState;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
