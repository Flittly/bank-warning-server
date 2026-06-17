package com.yangtze.bankwarning.security.service;

import com.yangtze.bankwarning.security.domain.dto.LoginRequest;
import com.yangtze.bankwarning.security.domain.dto.RegisterRequest;
import com.yangtze.bankwarning.security.domain.dto.UserResponse;
import com.yangtze.bankwarning.security.domain.po.UserPO;
import com.yangtze.bankwarning.security.exception.business.InvalidCredentialsException;
import com.yangtze.bankwarning.security.exception.business.UserNotFoundException;
import com.yangtze.bankwarning.security.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse register(RegisterRequest request) {
        if (userMapper.findByUsername(request.getUsername()) != null) {
            throw new InvalidCredentialsException("用户名已存在");
        }

        if (userMapper.findByPhone(request.getPhone()) != null) {
            throw new InvalidCredentialsException("手机号已注册");
        }

        UserPO user = new UserPO();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        user.setRealName(request.getRealName());
        user.setRole("USER");
        user.setStatus("ACTIVE");

        userMapper.insert(user);

        return convertToResponse(user);
    }

    public UserPO authenticate(LoginRequest request) {
        UserPO user = userMapper.findByUsername(request.getUsername());
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("密码错误");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new InvalidCredentialsException("用户账号已被禁用");
        }

        return user;
    }

    public UserResponse getUserInfo(Long userId) {
        UserPO user = userMapper.findById(userId);
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }
        return convertToResponse(user);
    }

    public UserResponse updateUserInfo(Long userId, UserResponse userInfo) {
        UserPO user = userMapper.findById(userId);
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }

        user.setPhone(userInfo.getPhone());
        user.setEmail(userInfo.getEmail());
        user.setRealName(userInfo.getRealName());
        user.setAvatar(userInfo.getAvatar());

        userMapper.update(user);

        return convertToResponse(user);
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
