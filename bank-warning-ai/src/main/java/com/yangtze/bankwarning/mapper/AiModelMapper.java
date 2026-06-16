package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.ai.domain.AiModelPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiModelMapper {
    List<AiModelPO> selectAll();
    AiModelPO selectByKey(@Param("modelKey") String modelKey);
    int insert(AiModelPO po);
    int deleteByKey(@Param("modelKey") String modelKey);
    int clearDefault();
    int setDefault(@Param("modelKey") String modelKey);
}
