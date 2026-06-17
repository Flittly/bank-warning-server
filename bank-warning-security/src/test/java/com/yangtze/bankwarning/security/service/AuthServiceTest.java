package com.yangtze.bankwarning.security.service;

import com.yangtze.bankwarning.security.domain.dto.LoginRequest;
import com.yangtze.bankwarning.security.domain.dto.RegisterRequest;
import com.yangtze.bankwarning.security.domain.dto.UserResponse;
import com.yangtze.bankwarning.security.domain.po.UserPO;
import com.yangtze.bankwarning.security.exception.business.InvalidCredentialsException;
import com.yangtze.bankwarning.security.exception.business.UserNotFoundException;
import com.yangtze.bankwarning.security.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;
    private UserPO existingUser;

    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest();
        registerRequest.setUsername("testuser");
        registerRequest.setPassword("password123");
        registerRequest.setPhone("13800138000");
        registerRequest.setEmail("test@example.com");
        registerRequest.setRealName("Test User");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");

        existingUser = new UserPO();
        existingUser.setId(1L);
        existingUser.setUsername("testuser");
        existingUser.setPassword("encoded_password");
        existingUser.setPhone("13800138000");
        existingUser.setEmail("test@example.com");
        existingUser.setRealName("Test User");
        existingUser.setRole("USER");
        existingUser.setStatus("ACTIVE");
    }

    @Test
    void register_Success() {
        when(userMapper.findByUsername("testuser")).thenReturn(null);
        when(userMapper.findByPhone("13800138000")).thenReturn(null);
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");
        when(userMapper.insert(any(UserPO.class))).thenReturn(1);

        UserResponse response = authService.register(registerRequest);

        assertNotNull(response);
        assertEquals("testuser", response.getUsername());
        assertEquals("13800138000", response.getPhone());
        verify(userMapper).insert(any(UserPO.class));
    }

    @Test
    void register_UsernameExists_ThrowsException() {
        when(userMapper.findByUsername("testuser")).thenReturn(existingUser);

        assertThrows(InvalidCredentialsException.class, () -> authService.register(registerRequest));
        verify(userMapper, never()).insert(any());
    }

    @Test
    void register_PhoneExists_ThrowsException() {
        when(userMapper.findByUsername("testuser")).thenReturn(null);
        when(userMapper.findByPhone("13800138000")).thenReturn(existingUser);

        assertThrows(InvalidCredentialsException.class, () -> authService.register(registerRequest));
        verify(userMapper, never()).insert(any());
    }

    @Test
    void authenticate_Success() {
        when(userMapper.findByUsername("testuser")).thenReturn(existingUser);
        when(passwordEncoder.matches("password123", "encoded_password")).thenReturn(true);

        UserPO result = authService.authenticate(loginRequest);

        assertNotNull(result);
        assertEquals("testuser", result.getUsername());
    }

    @Test
    void authenticate_UserNotFound_ThrowsException() {
        when(userMapper.findByUsername("testuser")).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> authService.authenticate(loginRequest));
    }

    @Test
    void authenticate_WrongPassword_ThrowsException() {
        when(userMapper.findByUsername("testuser")).thenReturn(existingUser);
        when(passwordEncoder.matches("password123", "encoded_password")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(loginRequest));
    }

    @Test
    void authenticate_UserInactive_ThrowsException() {
        existingUser.setStatus("INACTIVE");
        when(userMapper.findByUsername("testuser")).thenReturn(existingUser);
        when(passwordEncoder.matches("password123", "encoded_password")).thenReturn(true);

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(loginRequest));
    }

    @Test
    void getUserInfo_Success() {
        when(userMapper.findById(1L)).thenReturn(existingUser);

        UserResponse response = authService.getUserInfo(1L);

        assertNotNull(response);
        assertEquals("testuser", response.getUsername());
        assertEquals("13800138000", response.getPhone());
    }

    @Test
    void getUserInfo_UserNotFound_ThrowsException() {
        when(userMapper.findById(1L)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> authService.getUserInfo(1L));
    }

    @Test
    void updateUserInfo_Success() {
        when(userMapper.findById(1L)).thenReturn(existingUser);
        when(userMapper.update(any(UserPO.class))).thenReturn(1);

        UserResponse updateInfo = new UserResponse();
        updateInfo.setPhone("13900139000");
        updateInfo.setEmail("new@example.com");
        updateInfo.setRealName("New Name");

        UserResponse response = authService.updateUserInfo(1L, updateInfo);

        assertNotNull(response);
        assertEquals("13900139000", response.getPhone());
        assertEquals("new@example.com", response.getEmail());
        verify(userMapper).update(any(UserPO.class));
    }

    @Test
    void updateUserInfo_UserNotFound_ThrowsException() {
        when(userMapper.findById(1L)).thenReturn(null);

        UserResponse updateInfo = new UserResponse();
        assertThrows(UserNotFoundException.class, () -> authService.updateUserInfo(1L, updateInfo));
    }
}
