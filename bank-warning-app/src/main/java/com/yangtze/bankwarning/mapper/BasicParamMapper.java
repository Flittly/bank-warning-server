package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.BasicParamPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BasicParamMapper {

    List<BasicParamPO> selectAll(@Param("userId") Long userId);

    BasicParamPO selectByParamId(@Param("paramId") String paramId, @Param("userId") Long userId);

    BasicParamPO selectById(@Param("id") Integer id, @Param("userId") Long userId);

    int insert(BasicParamPO param);

    int update(BasicParamPO param);
}
