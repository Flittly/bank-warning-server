package com.yangtze.bankwarning.security.mapper;

import com.yangtze.bankwarning.security.domain.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    
    UserPO findByUsername(@Param("username") String username);
    
    UserPO findByPhone(@Param("phone") String phone);
    
    UserPO findById(@Param("id") Long id);
    
    int insert(UserPO user);
    
    int update(UserPO user);
    
    int updateStatus(@Param("id") Long id, @Param("status") String status);
    
    int updateRole(@Param("id") Long id, @Param("role") String role);
}
