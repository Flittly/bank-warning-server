package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.BankPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BankMapper {

    List<BankPO> selectAll(@Param("reachGroup") String reachGroup, @Param("userId") Long userId);

    BankPO selectByBankId(@Param("bankId") String bankId, @Param("userId") Long userId);

    /**
     * 从给定 id 里筛出「库里真实存在且未删」的岸段，用于复制任务时清洗 {@code tasks.bank_ids}。
     *
     * <p>一次查完而不是逐个 {@link #selectByBankId}：任务可能关联上百条岸段，
     * 逐条查会把「读关联」变成比「复制」还慢的事。
     */
    List<String> selectExistingBankIds(@Param("bankIds") List<String> bankIds, @Param("userId") Long userId);

    int insert(BankPO bank);

    int update(BankPO bank);

    /**
     * 只更新几何。
     *
     * <p>为什么要单开一条而不是复用 {@link #update}：那条 SQL 是全字段覆盖，
     * 前端只想改折点时若漏传 {@code bank_name} / {@code reach_group}，会把它们写成 null。
     * 折点编辑是高频小操作，不该要求调用方持有岸段的完整快照。
     */
    int updateGeometry(@Param("bankId") String bankId,
                       @Param("bankGeometry") String bankGeometry,
                       @Param("userId") Long userId);

    /**
     * 该岸段当前被多少任务 / 断面引用。编辑几何前给用户看影响面。
     *
     * <p>岸段是用户级共享资源（{@code banks} 表没有 task_id），改一条会同时改变
     * 所有引用它的任务的色带位置，所以必须能提前说出「会影响几个任务」。
     */
    java.util.Map<String, Object> selectUsageByBankId(@Param("bankId") String bankId,
                                                      @Param("userId") Long userId);

    int deleteByBankId(@Param("bankId") String bankId, @Param("userId") Long userId);
}
