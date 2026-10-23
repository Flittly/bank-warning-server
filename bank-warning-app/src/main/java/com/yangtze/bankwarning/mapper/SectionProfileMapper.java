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

    /** 该任务的有效剖面条数（复制任务的幂等重试要回真实行数，不能靠猜）。 */
    int countByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    /**
     * 复制任务的断面剖面。映射规则与 {@code SectionMapper.copySectionsFromTask} 一致：
     * 按 {@code srcPrefix} 切片重写 section_id，只搬未删行。
     */
    int copyFromTask(@Param("srcTaskId") String srcTaskId,
                     @Param("newTaskId") String newTaskId,
                     @Param("srcPrefix") String srcPrefix,
                     @Param("targetUserId") Long targetUserId);
}
