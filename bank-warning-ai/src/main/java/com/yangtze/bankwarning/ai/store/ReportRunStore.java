package com.yangtze.bankwarning.ai.store;

import java.util.Optional;

/**
 * AI 报告工作流运行状态存储（P0-1 Phase 1）。
 *
 * 把报告生成工作流的进度从进程内存搬到数据库：
 *   - 每个 task 同一时刻最多一个 PENDING/RUNNING 运行（部分唯一索引兜底）；
 *   - 步骤状态与中间上下文以 JSON 落库，服务重启后可从中断处续跑；
 *   - 服务启动时把所有遗留 RUNNING 标记为 FAILED（单实例假设），
 *     下次触发自动从最近 DONE 步骤继续。
 */
public interface ReportRunStore {

    String STATUS_PENDING = "PENDING";
    String STATUS_RUNNING = "RUNNING";
    String STATUS_FINISHED = "FINISHED";
    String STATUS_FAILED = "FAILED";

    /** 创建新运行（PENDING）。同 task 已有活动运行时依赖唯一索引拒绝。 */
    ReportRun create(String runId, String taskId, Long userId,
                     String stepStatesJson, String contextJson);

    /** 查询某 task 最近一次运行（按 id 倒序）。 */
    Optional<ReportRun> findLatestByTaskId(String taskId, Long userId);

    /** PENDING/FAILED -> RUNNING 的条件迁移；已被占用或已终态时返回 false。 */
    boolean claimRunning(String runId, Long userId);

    /** 更新步骤进度与上下文（仅 RUNNING 状态生效）。 */
    void updateProgress(String runId, Long userId, int currentStep,
                        String stepStatesJson, String contextJson);

    /** RUNNING -> FAILED（保留错误信息）。 */
    void markFailed(String runId, Long userId, String error);

    /** RUNNING -> FINISHED（记录报告文件名）。 */
    void markFinished(String runId, Long userId, String reportFileName);

    /** 启动清理：把遗留 RUNNING 标记为 FAILED（单实例重启场景）。 */
    int markStaleRunsFailed(String error);

    /** 一条运行记录 */
    record ReportRun(Long id, String runId, String taskId, Long userId, String status,
                     int currentStep, String stepStatesJson, String contextJson,
                     String lastError, String reportFileName,
                     String createdAt, String startedAt, String updatedAt, String completedAt) {
    }
}
