package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.repository.TaskRunRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

// 任务运行状态服务：负责管理一次运行实例的状态汇总
@Service
public class TaskRunStateService {

    private final TaskRunRepository taskRunRepository;

    public TaskRunStateService(TaskRunRepository taskRunRepository) {
        this.taskRunRepository = taskRunRepository;
    }

    // 创建一次新的运行，生成 runId
    public String createRun(String taskId, int expectedCount) {
        // 用 UUID 生成唯一的运行实例 ID
        String runId = UUID.randomUUID().toString();
        taskRunRepository.createRun(runId, taskId, expectedCount);
        return runId;
    }

    // 标记一个断面计算成功
    public void markSectionSuccess(String runId) {
        // 先确保状态是 running
        taskRunRepository.markSubmittedToRunning(runId);
        // 增加成功计数
        taskRunRepository.incrementCompleted(runId);
        // 检查是否全部完成
        tryFinish(runId);
    }

    // 标记一个断面计算失败
    public void markSectionError(String runId, String errorMessage) {
        // 先确保状态是 running
        taskRunRepository.markSubmittedToRunning(runId);
        // 增加失败计数
        taskRunRepository.incrementFailed(runId, errorMessage);
        // 检查是否全部完成
        tryFinish(runId);
    }

    // 获取运行汇总信息
    public Map<String, Object> getRunSummary(String runId) {
        Map<String, Object> run = taskRunRepository.getByRunId(runId);
        if (run == null) {
            throw new IllegalArgumentException("Run not found: " + runId);
        }
        return run;
    }

    // 内部方法：尝试结束本次运行
    private void tryFinish(String runId) {
        Map<String, Object> run = getRunSummary(runId);
        int expected = ((Number) run.get("expected_count")).intValue();
        int completed = ((Number) run.get("completed_count")).intValue();
        int failed = ((Number) run.get("failed_count")).intValue();

        // 如果已完成 + 已失败 >= 预期总数，说明全部返回了
        if (completed + failed >= expected) {
            if (failed > 0) {
                // 有失败，标记整体状态为 error
                taskRunRepository.markError(runId, String.valueOf(run.get("error_message")));
            } else {
                // 全部成功，标记整体状态为 completed
                taskRunRepository.markCompleted(runId);
            }
        }
    }
}