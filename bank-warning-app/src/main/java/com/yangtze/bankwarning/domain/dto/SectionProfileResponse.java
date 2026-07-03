package com.yangtze.bankwarning.domain.dto;

import com.yangtze.bankwarning.domain.po.SectionProfilePO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record SectionProfileResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("task_id") String taskId,
    @JsonProperty("section_id") String sectionId,
    @JsonProperty("section_name") String sectionName,
    @JsonProperty("region_code") String regionCode,
    @JsonProperty("bank_id") String bankId,
    @JsonProperty("dem_id") String demId,
    @JsonProperty("source_case_id") String sourceCaseId,
    @JsonProperty("interval") Double interval,
    @JsonProperty("deepest_index") Integer deepestIndex,
    @JsonProperty("slope_foot_index") Integer slopeFootIndex,
    @JsonProperty("point_count") Integer pointCount,
    @JsonProperty("profile_data") Object profileData,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static SectionProfileResponse from(SectionProfilePO po) {
        if (po == null) return null;
        return new SectionProfileResponse(
            po.getId(), po.getTaskId(), po.getSectionId(), po.getSectionName(),
            po.getRegionCode(), po.getBankId(), po.getDemId(), po.getSourceCaseId(),
            po.getInterval(), po.getDeepestIndex(), po.getSlopeFootIndex(),
            po.getPointCount(), JsonUtil.parse(po.getProfileData()),
            po.getCreatedAt(), po.getUpdatedAt()
        );
    }
}
