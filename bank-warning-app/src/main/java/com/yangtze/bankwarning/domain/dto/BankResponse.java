package com.yangtze.bankwarning.domain.dto;

import com.yangtze.bankwarning.domain.po.BankPO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record BankResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("bank_id") String bankId,
    @JsonProperty("bank_name") String bankName,
    @JsonProperty("region_code") String regionCode,
    @JsonProperty("bank_geometry") Object bankGeometry,
    @JsonProperty("reversed") Boolean reversed,
    @JsonProperty("description") String description,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static BankResponse from(BankPO po) {
        if (po == null) return null;
        return new BankResponse(
            po.getId(), po.getBankId(), po.getBankName(), po.getRegionCode(),
            JsonUtil.parse(po.getBankGeometry()), po.getReversed(), po.getDescription(),
            po.getCreatedAt(), po.getUpdatedAt()
        );
    }
}
