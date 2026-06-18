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
    private String segment;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
