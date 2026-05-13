package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.TaskRunPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskRunMapper {

    int insert(TaskRunPO taskRun);

    TaskRunPO selectByRunId(@Param("runId") String runId);

    int markSubmittedToRunning(@Param("runId") String runId);

    int incrementCompleted(@Param("runId") String runId);

    int incrementFailed(@Param("runId") String runId, @Param("errorMessage") String errorMessage);

    int markCompleted(@Param("runId") String runId);

    int markError(@Param("runId") String runId, @Param("errorMessage") String errorMessage);

    int markPartialFailed(@Param("runId") String runId, @Param("errorMessage") String errorMessage);
}
