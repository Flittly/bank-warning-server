package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.TaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskMapper {

    List<TaskPO> selectAll(@Param("userId") Long userId);

    TaskPO selectByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    /**
     * 取任务行，**包含已软删的**。
     *
     * <p>复制任务时用来判断「客户端指定的 target_task_id 是否已被占用」：
     * {@code tasks.task_id} 的 UNIQUE 约束不带 {@code deleted_at IS NULL} 条件，
     * 软删的行照样占着 id，盲插会直接撞唯一键。
     */
    TaskPO selectByTaskIdIncludingDeleted(@Param("taskId") String taskId, @Param("userId") Long userId);

    int insert(TaskPO task);

    int update(TaskPO task);

    /**
     * 只改 bank_ids 一列（岸段与任务的关联关系）。
     *
     * <p>刻意不复用 {@link #update(TaskPO)}：那条语句会连任务名、描述一起写回，
     * 而「给任务补一条岸段」不该顺带把用户可能在别处改过的名称覆盖成前端缓存的旧值。
     */
    int updateBankIds(@Param("taskId") String taskId,
                      @Param("bankIds") String bankIds,
                      @Param("userId") Long userId);

    int deleteByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    int updateStatus(@Param("taskId") String taskId,
                     @Param("status") String status,
                     @Param("runStartedAt") String runStartedAt,
                     @Param("runCompletedAt") String runCompletedAt,
                     @Param("errorMessage") String errorMessage,
                     @Param("userId") Long userId);

    int markRunning(@Param("taskId") String taskId, @Param("userId") Long userId);

    int markCompleted(@Param("taskId") String taskId, @Param("userId") Long userId);

    int markError(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage, @Param("userId") Long userId);

    int markPartialFailed(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage, @Param("userId") Long userId);

    /**
     * 标记为「已终止」。同样写 run_completed_at —— 前端 {@code isTaskCompleted} 靠它判断收尾，
     * 结果页的进度轮询据此停下来，否则终止后进度条会一直转。
     */
    int markCancelled(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage, @Param("userId") Long userId);
}
