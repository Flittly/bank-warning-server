package com.yangtze.bankwarning.ai.mapper;

import com.yangtze.bankwarning.ai.domain.po.WorkbenchConfigPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface WorkbenchConfigMapper {
    List<WorkbenchConfigPO> selectByUserId(@Param("userId") Long userId);
    WorkbenchConfigPO selectById(@Param("id") Long id, @Param("userId") Long userId);
    int insert(WorkbenchConfigPO po);
    int deleteById(@Param("id") Long id, @Param("userId") Long userId);
}
