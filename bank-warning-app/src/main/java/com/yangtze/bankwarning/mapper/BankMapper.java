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

    int deleteByBankId(@Param("bankId") String bankId, @Param("userId") Long userId);
}
