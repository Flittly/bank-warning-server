package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.RiskResultPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface RiskResultMapper {

    List<RiskResultPO> selectByTaskIdAndBankIdAndRegionCode(@Param("taskId") String taskId,
                                                            @Param("bankId") String bankId,
                                                            @Param("regionCode") String regionCode,
                                                            @Param("userId") Long userId);

    RiskResultPO selectLatestBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);

    int insert(RiskResultPO result);

    int countByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    /**
     * 每个任务的有效结果条数（一次查全表分组，供任务列表判断"该任务能不能框选分析"）。
     *
     * <p>刻意不按任务逐个 `countByTaskId`：任务列表一次要渲染几十条，N 次往返比一次
     * 分组查询慢一个数量级，而且前端还要为每条挂一个"检查中"状态。返回行键为
     * {@code task_id} / {@code cnt}。
     */
    List<Map<String, Object>> countGroupedByTask(@Param("userId") Long userId);

    int deleteByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    boolean existsByRunIdAndSectionId(@Param("runId") String runId, @Param("sectionId") String sectionId, @Param("userId") Long userId);

    /** 作废该断面的全部有效结果（软删）。单断面重算前调用，避免同一断面出现两行有效结论。 */
    int deleteBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);

    /** 该断面当前有效结果的行数。几何变更时用来判断要不要自动重算。 */
    int countActiveBySectionId(@Param("sectionId") String sectionId, @Param("userId") Long userId);

    /** 人工修改：原地改写 indicators / risk_level，并把模型原始快照写进 edit_state。 */
    int updateManualOverride(@Param("id") Long id,
                             @Param("indicators") String indicators,
                             @Param("riskLevel") Integer riskLevel,
                             @Param("editState") String editState,
                             @Param("userId") Long userId);

    /** 恢复模型原值：从 edit_state 取回快照并清空标记。返回 0 表示本来就没有修改记录。 */
    int revertManualOverride(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 人工新建一条结果（该断面本来没有任何行）。
     *
     * <p>与 {@link #insert} 的区别只有一个：带上 `edit_state`，并且**不写 run_id**。
     * run_id 是模型那一轮的标识，人工录入没有轮次；留 NULL 才能让「这行不是模型算的」
     * 在库里可分辨（出图/报告只读 indicators，不受影响）。
     */
    int insertManualOverride(RiskResultPO result);

    /** 按 id 软删一行。人工新建的结果点「恢复模型原值」时用它 —— 原本就没有原值，撤销 = 删掉。 */
    int softDeleteById(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 取该任务任一结果的 indicators JSON（取最新一行）。
     *
     * <p>用途：无结果断面要人工录入时，得先拿到**这个任务**的 sub_thresholds / 权重 / all 阈值。
     * 同一任务下所有断面的参数完全相同（都来自同一份任务参数快照），所以取任意一行即可；
     * 拿不到（该任务一条结果都没有）才由调用方回落默认值。
     */
    String selectIndicatorsByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    /**
     * 复制任务的计算结果（仅 {@code include_results=true} 时调用）。
     *
     * <p>刻意 `ORDER BY r.id`：{@code selectLatestBySectionId} 取「最新」用的是
     * `ORDER BY id DESC LIMIT 1`，同一断面存在多行时（单断面重算只 INSERT 不删旧行），
     * 插入顺序决定副本里「最新一行」是不是原件里的那一行。
     *
     * @param srcPrefix `"sec-" + 源 taskId`，用于把旧 section_id 映射到新 id
     * @return 实际插入的行数
     */
    int copyFromTask(@Param("srcTaskId") String srcTaskId,
                     @Param("newTaskId") String newTaskId,
                     @Param("srcPrefix") String srcPrefix,
                     @Param("targetUserId") Long targetUserId);
}
