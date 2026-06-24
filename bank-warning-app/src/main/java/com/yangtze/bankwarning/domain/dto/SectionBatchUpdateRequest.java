package com.yangtze.bankwarning.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record SectionBatchUpdateRequest(
        @JsonProperty("section_ids") List<String> sectionIds,
        Map<String, Object> params
) {}
