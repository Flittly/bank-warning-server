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

    /** 人工修改：原地改写 indicators / risk_level，并把模型原始快照写进 edit_state。 */
    int updateManualOverride(@Param("id") Long id,
                             @Param("indicators") String indicators,
                             @Param("riskLevel") Integer riskLevel,
                             @Param("editState") String editState,
                             @Param("userId") Long userId);

    /** 恢复模型原值：从 edit_state 取回快照并清空标记。返回 0 表示本来就没有修改记录。 */
    int revertManualOverride(@Param("id") Long id, @Param("userId") Long userId);
}
