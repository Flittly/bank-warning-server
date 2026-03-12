package com.yangtze.bankwarning.repository;

import com.yangtze.bankwarning.dto.TaskPayload;
import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TaskRepository extends AbstractJdbcRepository {

    public TaskRepository(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    public Map<String, Object> save(TaskPayload payload, boolean overwrite) {
        if (overwrite && exists("SELECT 1 FROM tasks WHERE task_id = :taskId", params("taskId", payload.taskId()))) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("taskName", payload.taskName());
            args.put("bankIds", writeJson(payload.bankIds()));
            args.put("description", payload.description());
            args.put("taskId", payload.taskId());
            update(
                    "UPDATE tasks SET task_name = :taskName, bank_ids = CAST(:bankIds AS jsonb), description = :description WHERE task_id = :taskId",
                    args);
            return getByTaskId(payload.taskId());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("taskId", payload.taskId());
        args.put("taskName", payload.taskName());
        args.put("bankIds", writeJson(payload.bankIds()));
        args.put("description", payload.description());
        update(
                "INSERT INTO tasks (task_id, task_name, bank_ids, description) VALUES (:taskId, :taskName, CAST(:bankIds AS jsonb), :description)",
                args);
        return getByTaskId(payload.taskId());
    }

    public List<Map<String, Object>> list() {
        return queryList("SELECT * FROM tasks ORDER BY id DESC", Map.of());
    }

    public Map<String, Object> getByTaskId(String taskId) {
        return queryOne("SELECT * FROM tasks WHERE task_id = :taskId", params("taskId", taskId));
    }

    public void updateStatus(String taskId, String status, String runStartedAt, String runCompletedAt, String errorMessage) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("status", status);
        args.put("runStartedAt", runStartedAt);
        args.put("runCompletedAt", runCompletedAt);
        args.put("errorMessage", errorMessage);
        args.put("taskId", taskId);
        update(
                """
                UPDATE tasks
                SET status = COALESCE(:status, status),
                    run_started_at = COALESCE(CAST(:runStartedAt AS TIMESTAMP), run_started_at),
                    run_completed_at = COALESCE(CAST(:runCompletedAt AS TIMESTAMP), run_completed_at),
                    error_message = :errorMessage
                WHERE task_id = :taskId
                """,
                args);
    }

    public void markRunning(String taskId) {
        update("UPDATE tasks SET status = 'running', run_started_at = CURRENT_TIMESTAMP, run_completed_at = NULL, error_message = NULL WHERE task_id = :taskId", params("taskId", taskId));
    }

    public void markCompleted(String taskId) {
        update("UPDATE tasks SET status = 'completed', run_completed_at = CURRENT_TIMESTAMP, error_message = NULL WHERE task_id = :taskId", params("taskId", taskId));
    }

    public void markError(String taskId, String errorMessage) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("errorMessage", errorMessage);
        args.put("taskId", taskId);
        update("UPDATE tasks SET status = 'error', run_completed_at = CURRENT_TIMESTAMP, error_message = :errorMessage WHERE task_id = :taskId", args);
    }

    public int deleteByTaskId(String taskId) {
        return update("DELETE FROM tasks WHERE task_id = :taskId", params("taskId", taskId));
    }
}
