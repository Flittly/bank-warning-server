package com.yangtze.bankwarning.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SectionsCreateRequest(
        @JsonProperty("task_id") String taskId,
        List<SectionPayload> sections,
        @JsonProperty("inherit_from_basic_param") Boolean inheritFromBasicParam,
        Boolean overwrite) {
}
