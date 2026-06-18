package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SectionProfilePO {
    private Long id;
    private Long userId;
    private String taskId;
    private String sectionId;
    private String sectionName;
    private String regionCode;
    private String bankId;
    private String demId;
    private String sourceCaseId;
    private Double interval;
    private Integer deepestIndex;
    private Integer slopeFootIndex;
    private Integer pointCount;
    private String profileData;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
