package com.yangtze.bankwarning.repository;

import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class TaskRunRepository extends AbstractJdbcRepository {
    public TaskRunRepository(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    //创建一次新的运行记录
    public void createRun(String runId, String taskId, int expectedCount) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("runId", runId);
        args.put("taskId", taskId);
        args.put("expectedCount", expectedCount);
        update(
                """
                INSERT INTO task_runs (run_id, task_id, status, expected_count, completed_count, failed_count)
                VALUES (:runId, :taskId, 'submitted', :expectedCount, 0, 0)
                """,
                args
        );
    }

    //根据runId获取运行记录
    public Map<String, Object> getByRunId(String runId) {
        return queryOne("SELECT * FROM task_runs WHERE run_id = :runId", params("runId", runId));
    }

    //将状态从 submitted 更新为 running
    public void markSubmittedToRunning(String runId) {
        update(
                "UPDATE task_runs SET status = 'running' WHERE run_id = :runId AND status = 'submitted'",
                params("runId", runId)
        );
    }

    //增加成功计数
    public void incrementCompleted(String runId) {
        update(
                "UPDATE task_runs SET completed_count = completed_count + 1, status = 'running' WHERE run_id = :runId",
                params("runId", runId)
        );
    }

    // 增加失败计数
    public void incrementFailed(String runId, String errorMessage) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("runId", runId);
        args.put("errorMessage", errorMessage);
        update(
                "UPDATE task_runs SET failed_count = failed_count + 1, status = 'running', error_message = :errorMessage WHERE run_id = :runId",
                args
        );
    }

    // 标记本次运行完成
    public void markCompleted(String runId) {
        update(
                "UPDATE task_runs SET status = 'completed', completed_at = CURRENT_TIMESTAMP WHERE run_id = :runId",
                params("runId", runId)
        );
    }

    //标记本次运行失败
    public void markError(String runId, String errorMessage) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("runId", runId);
        args.put("errorMessage", errorMessage);
        update(
                "UPDATE task_runs SET status = 'error', completed_at = CURRENT_TIMESTAMP, error_message = :errorMessage WHERE run_id = :runId",
                args
        );
    }
}
