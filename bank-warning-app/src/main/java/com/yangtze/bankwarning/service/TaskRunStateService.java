package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.TaskRunPO;
import com.yangtze.bankwarning.mapper.TaskRunMapper;
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
        taskRunMapper.insert(po);
        return runId;
    }

    @Override
    public void markSectionSuccess(String runId) {
        taskRunMapper.markSubmittedToRunning(runId);
        taskRunMapper.incrementCompleted(runId);
        tryFinish(runId);
    }

    @Override
    public void markSectionError(String runId, String errorMessage) {
        taskRunMapper.markSubmittedToRunning(runId);
        taskRunMapper.incrementFailed(runId, errorMessage);
        tryFinish(runId);
    }

    public Map<String, Object> getRunSummary(String runId) {
        TaskRunPO po = taskRunMapper.selectByRunId(runId);
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
            if (failed > 0) {
                if (completed > 0) {
                    taskRunMapper.markPartialFailed(runId, String.valueOf(run.get("error_message")));
                } else {
                    taskRunMapper.markError(runId, String.valueOf(run.get("error_message")));
                }
            } else {
                taskRunMapper.markCompleted(runId);
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
