package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.SectionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

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

    /** 该任务的第一条断面（任务参数物化时的取值来源）。 */
    SectionPO selectFirstByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    /** 把本任务所有断面重新指向该任务的参数集（只改归属，不动参数值）。 */
    int assignBasicParamForTask(@Param("taskId") String taskId, @Param("basicParamId") Integer basicParamId, @Param("userId") Long userId);

    int batchUpdateParams(@Param("sectionIds") List<String> sectionIds, @Param("params") Map<String, Object> params, @Param("userId") Long userId);

    /**
     * 把断面上的参数快照刷新为**本任务参数集**的当前值，作用域严格限定在一个任务内。
     *
     * 为什么必须带 task_id：basic_params 里 task_id IS NULL 的行是模板库主数据
     * （一个模板可能被多个历史任务复用），按模板全量下发会一次性改写所有历史任务的输入，
     * 破坏任务的可复现性。任务内刷新是唯一允许的批量语义。
     */
    int refreshParamsForTask(@Param("taskId") String taskId, @Param("userId") Long userId);

    /** 统计该任务下有多少断面的参数快照与本任务参数集不一致（只读）。 */
    int countParamDiffsForTask(@Param("taskId") String taskId, @Param("userId") Long userId);

    int reverseSectionGeometry(@Param("sectionId") String sectionId, @Param("sectionGeometry") String sectionGeometry, @Param("otherParams") String otherParams, @Param("userId") Long userId);
}
