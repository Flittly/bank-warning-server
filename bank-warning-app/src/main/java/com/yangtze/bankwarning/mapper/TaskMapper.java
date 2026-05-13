package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.TaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskMapper {

    List<TaskPO> selectAll();

    TaskPO selectByTaskId(@Param("taskId") String taskId);

    int insert(TaskPO task);

    int update(TaskPO task);

    int deleteByTaskId(@Param("taskId") String taskId);

    int updateStatus(@Param("taskId") String taskId,
                     @Param("status") String status,
                     @Param("runStartedAt") String runStartedAt,
                     @Param("runCompletedAt") String runCompletedAt,
                     @Param("errorMessage") String errorMessage);

    int markRunning(@Param("taskId") String taskId);

    int markCompleted(@Param("taskId") String taskId);

    int markError(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage);

    int markPartialFailed(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage);
}
