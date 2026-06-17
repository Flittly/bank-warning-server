package com.yangtze.bankwarning.security.controller;

import com.yangtze.bankwarning.security.domain.dto.LoginRequest;
import com.yangtze.bankwarning.security.domain.dto.RegisterRequest;
import com.yangtze.bankwarning.security.domain.dto.UserResponse;
import com.yangtze.bankwarning.security.domain.po.UserPO;
import com.yangtze.bankwarning.security.security.JwtTokenProvider;
import com.yangtze.bankwarning.security.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private AuthService authService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthController authController;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

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
    }

    @Test
    void register_Success() {
        UserResponse userResponse = new UserResponse();
        userResponse.setId(1L);
        userResponse.setUsername("testuser");
        userResponse.setPhone("13800138000");

        when(authService.register(any(RegisterRequest.class))).thenReturn(userResponse);

        ResponseEntity<UserResponse> response = authController.register(registerRequest);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("testuser", response.getBody().getUsername());
        assertEquals("13800138000", response.getBody().getPhone());
    }

    @Test
    void login_Success() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "testuser", "password123",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(auth);
        when(jwtTokenProvider.generateToken(any(Authentication.class))).thenReturn("jwt-token");
        when(jwtTokenProvider.generateRefreshToken(any(Authentication.class))).thenReturn("refresh-token");

        ResponseEntity<Map<String, Object>> response = authController.login(loginRequest);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("jwt-token", response.getBody().get("accessToken"));
        assertEquals("refresh-token", response.getBody().get("refreshToken"));
        assertEquals("Bearer", response.getBody().get("tokenType"));
    }

    @Test
    void getUserInfo_Success() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "testuser", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        ResponseEntity<UserResponse> response = authController.getUserInfo();

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("testuser", response.getBody().getUsername());
    }
}
