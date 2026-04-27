package com.yangtze.bankwarning.service.async;

public interface TaskRunStatePort {
    String createRun(String taskId, int expectedCount);

    void markSectionSuccess(String runId);

    void markSectionError(String runId, String errorMessage);
}
