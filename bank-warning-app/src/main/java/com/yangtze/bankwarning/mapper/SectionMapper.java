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

    /**
     * 该任务的断面里出现过、且**确实存在于岸段库**的 bank_id。
     *
     * <p>用于修复 `tasks.bank_ids` 漏登记：早年的「补充岸段」在写关联之前就失败了
     * （接口不存在 / 请求被中断），断面已落库、bank_ids 却没写进去 ——
     * 表现为「树里 17 条、地图上 19 条」，以及整任务重建时那几条岸段被静默丢掉。
     *
     * <p>为什么 JOIN banks：自由断面（手工画线）的 bank_id 是 `line-N` 占位符，
     * 岸段库里根本没有，写进 bank_ids 只会让前端每次加载都去请求一个 404。
     */
    List<String> selectDistinctRealBankIdsByTask(@Param("taskId") String taskId, @Param("userId") Long userId);

    int reverseSectionGeometry(@Param("sectionId") String sectionId, @Param("sectionGeometry") String sectionGeometry, @Param("otherParams") String otherParams, @Param("userId") Long userId);

    /**
     * 复制任务的断面：把 {@code srcTaskId} 下所有未删断面整体搬到 {@code newTaskId}。
     *
     * <p>{@code srcPrefix = "sec-" + srcTaskId}，用于切片出原 section_id 的后缀；
     * {@code newParamId} 是新任务的参数行 id（可为 null，表示不复制参数集）；
     * {@code resetValidation=true} 时把校验结论打回 pending。
     *
     * @return 实际插入的行数
     */
    int copySectionsFromTask(@Param("srcTaskId") String srcTaskId,
                             @Param("newTaskId") String newTaskId,
                             @Param("srcPrefix") String srcPrefix,
                             @Param("newParamId") Integer newParamId,
                             @Param("targetUserId") Long targetUserId,
                             @Param("resetValidation") boolean resetValidation);
}
