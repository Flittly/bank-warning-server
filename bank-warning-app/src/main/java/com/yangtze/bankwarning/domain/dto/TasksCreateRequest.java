package com.yangtze.bankwarning.domain.dto;

import java.util.List;

public record TasksCreateRequest(
        List<TaskPayload> tasks,
        Boolean overwrite) {
}
