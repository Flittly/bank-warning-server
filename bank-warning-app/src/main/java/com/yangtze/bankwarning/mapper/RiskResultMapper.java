package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.RiskResultPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RiskResultMapper {

    List<RiskResultPO> selectByTaskIdAndBankIdAndRegionCode(@Param("taskId") String taskId,
                                                            @Param("bankId") String bankId,
                                                            @Param("regionCode") String regionCode);

    RiskResultPO selectLatestBySectionId(@Param("sectionId") String sectionId);

    int insert(RiskResultPO result);

    int countByTaskId(@Param("taskId") String taskId);

    int deleteByTaskId(@Param("taskId") String taskId);

    boolean existsByRunIdAndSectionId(@Param("runId") String runId, @Param("sectionId") String sectionId);
}
