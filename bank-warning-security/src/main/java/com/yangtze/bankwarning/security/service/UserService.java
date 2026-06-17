package com.yangtze.bankwarning.security.service;

import com.yangtze.bankwarning.security.domain.dto.UserResponse;
import com.yangtze.bankwarning.security.domain.po.UserPO;
import com.yangtze.bankwarning.security.exception.business.UserNotFoundException;
import com.yangtze.bankwarning.security.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<UserResponse> getAllUsers() {
        // 这里需要实现获取所有用户的逻辑
        // 简化处理，实际项目中应该有更完善的查询
        return List.of();
    }

    public UserResponse updateUserStatus(Long userId, String status) {
        UserPO user = userMapper.findById(userId);
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }

        userMapper.updateStatus(userId, status);

        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setStatus(status);
        return response;
    }

    public UserResponse updateUserRole(Long userId, String role) {
        UserPO user = userMapper.findById(userId);
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }

        userMapper.updateRole(userId, role);

        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(role);
        return response;
    }
}
