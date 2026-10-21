package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.SectionProfilePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SectionProfileMapper {

    List<SectionProfilePO> selectByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    SectionProfilePO selectLatestBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);

    int insertOrUpdate(SectionProfilePO profile);

    int deleteByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    /**
     * 作废单条断面的剖面（软删）。
     *
     * <p>断面几何一变，这份剖面就不再描述它了：{@code profile} 里的距离-高程点、
     * {@code deepest_index}、{@code point_count} 全都对应旧位置。留着只会让结果页
     * 拿一条错位的剖面图冒充当前断面。
     */
    int deleteBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);
}
