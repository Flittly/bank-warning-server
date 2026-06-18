package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.TaskRunPO;
import com.yangtze.bankwarning.mapper.TaskRunMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import com.yangtze.bankwarning.service.async.TaskRunStatePort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskRunStateService implements TaskRunStatePort {

    private final TaskRunMapper taskRunMapper;

    public TaskRunStateService(TaskRunMapper taskRunMapper) {
        this.taskRunMapper = taskRunMapper;
    }

    @Override
    public String createRun(String taskId, int expectedCount) {
        String runId = UUID.randomUUID().toString();
        TaskRunPO po = new TaskRunPO();
        po.setRunId(runId);
        po.setTaskId(taskId);
        po.setExpectedCount(expectedCount);
        po.setUserId(SecurityUtils.getCurrentUserId());
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskRunMapper.insert(po);
        return runId;
    }

    @Override
    public void markSectionSuccess(String runId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskRunMapper.markSubmittedToRunning(runId, userId);
        taskRunMapper.incrementCompleted(runId, userId);
        tryFinish(runId);
    }

    @Override
    public void markSectionError(String runId, String errorMessage) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskRunMapper.markSubmittedToRunning(runId, userId);
        taskRunMapper.incrementFailed(runId, errorMessage, userId);
        tryFinish(runId);
    }

    public Map<String, Object> getRunSummary(String runId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        TaskRunPO po = taskRunMapper.selectByRunId(runId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Run not found: " + runId);
        }
        return toMap(po);
    }

    private void tryFinish(String runId) {
        Map<String, Object> run = getRunSummary(runId);
        int expected = ((Number) run.get("expected_count")).intValue();
        int completed = ((Number) run.get("completed_count")).intValue();
        int failed = ((Number) run.get("failed_count")).intValue();

        if (completed + failed >= expected) {
            Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
            if (failed > 0) {
                if (completed > 0) {
                    taskRunMapper.markPartialFailed(runId, String.valueOf(run.get("error_message")), userId);
                } else {
                    taskRunMapper.markError(runId, String.valueOf(run.get("error_message")), userId);
                }
            } else {
                taskRunMapper.markCompleted(runId, userId);
            }
        }
    }

    private Map<String, Object> toMap(TaskRunPO po) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("run_id", po.getRunId());
        map.put("task_id", po.getTaskId());
        map.put("status", po.getStatus());
        map.put("expected_count", po.getExpectedCount());
        map.put("completed_count", po.getCompletedCount());
        map.put("failed_count", po.getFailedCount());
        map.put("error_message", po.getErrorMessage());
        map.put("created_at", po.getCreatedAt());
        map.put("started_at", po.getStartedAt());
        map.put("completed_at", po.getCompletedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }
}
