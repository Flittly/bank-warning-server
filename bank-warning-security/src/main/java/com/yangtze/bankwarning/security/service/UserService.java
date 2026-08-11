package com.yangtze.bankwarning.security.service;

import com.yangtze.bankwarning.security.domain.dto.UserResponse;
import com.yangtze.bankwarning.security.domain.po.UserPO;
import com.yangtze.bankwarning.security.exception.business.InvalidCredentialsException;
import com.yangtze.bankwarning.security.exception.business.PermissionDeniedException;
import com.yangtze.bankwarning.security.exception.business.UserNotFoundException;
import com.yangtze.bankwarning.security.mapper.UserMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class UserService {

    private static final List<String> VALID_STATUSES = Arrays.asList("ACTIVE", "INACTIVE", "PENDING");
    private static final List<String> VALID_ROLES = Arrays.asList("SUPER_ADMIN", "ADMIN", "USER");

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

        UserPO user = requireUser(userId);
        requireCanManage(user, null);

        userMapper.updateStatus(userId, status);

        UserResponse response = convertToResponse(user);
        response.setStatus(status);
        return response;
    }

    public UserResponse updateUserRole(Long userId, String role) {
        if (!VALID_ROLES.contains(role)) {
            throw new InvalidCredentialsException("无效的角色值，允许的值：" + VALID_ROLES);
        }

        UserPO user = requireUser(userId);
        requireCanManage(user, role);

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

    private UserPO requireUser(Long userId) {
        UserPO user = userMapper.findById(userId);
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }
        return user;
    }

    /**
     * 用户管理边界：超级管理员可管理任何人（除自己）；管理员只能管理普通用户，
     * 且只能授予 USER 角色；普通用户无管理权限。
     */
    private void requireCanManage(UserPO target, String newRole) {
        Long currentId = SecurityUtils.getCurrentUserId();
        String currentRole = SecurityUtils.getCurrentUserRole();
        if (currentId == null || currentRole == null) {
            throw new PermissionDeniedException("无法识别当前用户角色");
        }
        if (currentId.equals(target.getId())) {
            throw new PermissionDeniedException("不能修改自己的角色或状态");
        }
        if (!canManage(currentRole, target.getRole(), newRole)) {
            throw new PermissionDeniedException("无权执行该用户管理操作");
        }
    }

    /** 纯权限判断，便于单测：SUPER_ADMIN 可管理一切；ADMIN 只能管理 USER 且只能授予 USER */
    static boolean canManage(String currentRole, String targetRole, String newRole) {
        if ("SUPER_ADMIN".equals(currentRole)) {
            return true;
        }
        if ("ADMIN".equals(currentRole)) {
            return "USER".equals(targetRole) && (newRole == null || "USER".equals(newRole));
        }
        return false;
    }
}
