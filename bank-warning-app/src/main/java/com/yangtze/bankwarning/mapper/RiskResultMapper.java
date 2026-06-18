package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.RiskResultPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RiskResultMapper {

    List<RiskResultPO> selectByTaskIdAndBankIdAndRegionCode(@Param("taskId") String taskId,
                                                            @Param("bankId") String bankId,
                                                            @Param("regionCode") String regionCode,
                                                            @Param("userId") Long userId);

    RiskResultPO selectLatestBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);

    int insert(RiskResultPO result);

    int countByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    int deleteByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    boolean existsByRunIdAndSectionId(@Param("runId") String runId, @Param("sectionId") String sectionId, @Param("userId") Long userId);
}
