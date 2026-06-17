package com.yangtze.bankwarning.security.controller;

import com.yangtze.bankwarning.security.domain.dto.LoginRequest;
import com.yangtze.bankwarning.security.domain.dto.RegisterRequest;
import com.yangtze.bankwarning.security.domain.dto.UserResponse;
import com.yangtze.bankwarning.security.security.JwtTokenProvider;
import com.yangtze.bankwarning.security.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v0/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(AuthenticationManager authenticationManager,
                          AuthService authService,
                          JwtTokenProvider jwtTokenProvider) {
        this.authenticationManager = authenticationManager;
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse user = authService.register(request);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtTokenProvider.generateToken(authentication);
        String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);

        Map<String, Object> response = new HashMap<>();
        response.put("accessToken", jwt);
        response.put("refreshToken", refreshToken);
        response.put("tokenType", "Bearer");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/info")
    public ResponseEntity<UserResponse> getUserInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        
        // 从数据库获取完整用户信息
        // 注意：这里需要从UserDetails中获取用户ID，简化处理
        // 实际项目中应该在UserDetails中存储用户ID
        UserResponse user = authService.getUserInfoByUsername(username);
        
        return ResponseEntity.ok(user);
    }
}
