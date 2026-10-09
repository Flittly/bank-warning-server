package com.yangtze.bankwarning.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface HydroConditionMapper {

    List<Map<String, Object>> selectDistinctConditions();
}
