package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SectionPO {
    private Long id;
    private Long userId;
    private String taskId;
    private String sectionId;
    private String sectionName;
    private String bankId;
    private String regionCode;
    private Integer segmentIndex;
    private String sectionGeometry;
    private String verticalFootPoint;
    private Double distance;
    private Integer basicParamId;
    private String paramName;
    private String benchSegment;
    private String currentTimepoint;
    private String setName;
    private String waterQs;
    private String tidalLevel;
    private String benchId;
    private String refId;
    private Double hs;
    private Double hc;
    private String protectionLevel;
    private String controlLevel;
    private String comparisonTimepoint;
    private String riskThresholds;
    private String weights;
    private String otherParams;
    private Boolean isValid;
    private String validationStatus;
    private String validationMessage;
    /**
     * 人工标记为无效断面：不参与着色/统计/出图，图上以灰色虚线显示。
     *
     * <p>与 {@link #isValid} 是**两件事**：isValid 是几何校验的结论（会被重新校验覆写），
     * 这里是人的判断，只能由用户显式切换。详见 migration/V15。
     */
    private Boolean manualInvalid;
    private String manualInvalidReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
