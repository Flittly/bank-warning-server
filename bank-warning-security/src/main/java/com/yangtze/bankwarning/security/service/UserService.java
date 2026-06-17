package com.yangtze.bankwarning.security.service;

import com.yangtze.bankwarning.security.domain.dto.UserResponse;
import com.yangtze.bankwarning.security.domain.po.UserPO;
import com.yangtze.bankwarning.security.exception.business.InvalidCredentialsException;
import com.yangtze.bankwarning.security.exception.business.UserNotFoundException;
import com.yangtze.bankwarning.security.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class UserService {

    private static final List<String> VALID_STATUSES = Arrays.asList("ACTIVE", "INACTIVE", "PENDING");
    private static final List<String> VALID_ROLES = Arrays.asList("ADMIN", "USER");

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<UserResponse> getAllUsers() {
        List<UserPO> users = userMapper.findAll();
        return users.stream()
                .map(this::convertToResponse)
                .toList();
    }

    public UserResponse updateUserStatus(Long userId, String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new InvalidCredentialsException("无效的状态值，允许的值：" + VALID_STATUSES);
        }

        UserPO user = userMapper.findById(userId);
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }

        userMapper.updateStatus(userId, status);

        UserResponse response = convertToResponse(user);
        response.setStatus(status);
        return response;
    }

    public UserResponse updateUserRole(Long userId, String role) {
        if (!VALID_ROLES.contains(role)) {
            throw new InvalidCredentialsException("无效的角色值，允许的值：" + VALID_ROLES);
        }

        UserPO user = userMapper.findById(userId);
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }

        userMapper.updateRole(userId, role);

        UserResponse response = convertToResponse(user);
        response.setRole(role);
        return response;
    }

    private UserResponse convertToResponse(UserPO user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setPhone(user.getPhone());
        response.setEmail(user.getEmail());
        response.setRealName(user.getRealName());
        response.setAvatar(user.getAvatar());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        return response;
    }
}
