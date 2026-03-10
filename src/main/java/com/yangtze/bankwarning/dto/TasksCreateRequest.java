package com.yangtze.bankwarning.dto;

import java.util.List;

public record TasksCreateRequest(
        List<TaskPayload> tasks,
        Boolean overwrite) {
}
