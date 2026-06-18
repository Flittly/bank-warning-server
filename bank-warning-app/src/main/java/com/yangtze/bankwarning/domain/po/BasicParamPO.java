package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BasicParamPO {
    private Long id;
    private Long userId;
    private String paramId;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
