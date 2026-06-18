package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.SectionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SectionMapper {

    List<SectionPO> selectByTaskIdAndBankId(@Param("taskId") String taskId, @Param("bankId") String bankId, @Param("userId") Long userId);

    SectionPO selectBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);

    int insert(SectionPO section);

    int update(SectionPO section);

    int deleteBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);

    int countByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    int deleteByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    boolean existsBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);
}
