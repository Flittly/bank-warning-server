package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.BankPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BankMapper {

    List<BankPO> selectAll(@Param("regionCode") String regionCode);

    BankPO selectByBankId(@Param("bankId") String bankId);

    int insert(BankPO bank);

    int update(BankPO bank);

    int deleteByBankId(@Param("bankId") String bankId);
}
