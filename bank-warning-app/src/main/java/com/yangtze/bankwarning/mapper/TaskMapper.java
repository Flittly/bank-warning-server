package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.TaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskMapper {

    List<TaskPO> selectAll(@Param("userId") Long userId);

    TaskPO selectByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    int insert(TaskPO task);

    int update(TaskPO task);

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
