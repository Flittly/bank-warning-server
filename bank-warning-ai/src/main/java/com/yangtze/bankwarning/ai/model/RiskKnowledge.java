package com.yangtze.bankwarning.ai.model;

import lombok.Data;

@Data
public class RiskKnowledge {
    private String id;
    private String type;
    private String title;
    private String content;
    private String region;
    private String riskLevel;
    private String source;
}
