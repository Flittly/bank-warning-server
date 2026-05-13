package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.SectionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SectionMapper {

    List<SectionPO> selectByTaskIdAndBankId(@Param("taskId") String taskId, @Param("bankId") String bankId);

    SectionPO selectBySectionId(@Param("sectionId") String sectionId);

    int insert(SectionPO section);

    int update(SectionPO section);

    int deleteBySectionId(@Param("sectionId") String sectionId);

    int countByTaskId(@Param("taskId") String taskId);

    int deleteByTaskId(@Param("taskId") String taskId);

    boolean existsBySectionId(@Param("sectionId") String sectionId);
}
