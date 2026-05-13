package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.SectionProfilePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SectionProfileMapper {

    List<SectionProfilePO> selectByTaskId(@Param("taskId") String taskId);

    SectionProfilePO selectLatestBySectionId(@Param("sectionId") String sectionId);

    int insertOrUpdate(SectionProfilePO profile);

    int deleteByTaskId(@Param("taskId") String taskId);
}
