package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.TaskRunPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskRunMapper {

    int insert(TaskRunPO taskRun);

    TaskRunPO selectByRunId(@Param("runId") String runId, @Param("userId") Long userId);

    int markSubmittedToRunning(@Param("runId") String runId, @Param("userId") Long userId);

    int incrementCompleted(@Param("runId") String runId, @Param("userId") Long userId);

    int incrementFailed(@Param("runId") String runId, @Param("errorMessage") String errorMessage, @Param("userId") Long userId);

    int markCompleted(@Param("runId") String runId, @Param("userId") Long userId);

    int markError(@Param("runId") String runId, @Param("errorMessage") String errorMessage, @Param("userId") Long userId);

    int markPartialFailed(@Param("runId") String runId, @Param("errorMessage") String errorMessage, @Param("userId") Long userId);
}
