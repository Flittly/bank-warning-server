package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.BasicParamPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BasicParamMapper {

    List<BasicParamPO> selectAll();

    BasicParamPO selectByParamId(@Param("paramId") String paramId);

    BasicParamPO selectById(@Param("id") Integer id);

    int insert(BasicParamPO param);

    int update(BasicParamPO param);
}
