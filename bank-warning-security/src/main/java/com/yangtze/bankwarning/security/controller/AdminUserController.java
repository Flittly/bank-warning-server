package com.yangtze.bankwarning.security.controller;

import com.yangtze.bankwarning.security.domain.dto.UserResponse;
import com.yangtze.bankwarning.security.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v0/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<UserResponse> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    @PutMapping("/users/{id}/status")
    public ResponseEntity<UserResponse> updateUserStatus(
            @PathVariable Long id, 
            @RequestBody Map<String, String> request) {
        UserResponse user = userService.updateUserStatus(id, request.get("status"));
        return ResponseEntity.ok(user);
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<UserResponse> updateUserRole(
            @PathVariable Long id, 
            @RequestBody Map<String, String> request) {
        UserResponse user = userService.updateUserRole(id, request.get("role"));
        return ResponseEntity.ok(user);
    }

    /**
     * 强制用户下线
     * 
     * 注意：此功能当前为占位实现，尚未完成
     * 完整实现需要：
     * 1. 引入Redis用于存储token黑名单
     * 2. 在JwtAuthenticationFilter中检查黑名单
     * 3. 删除用户的refresh token
     */
    @PostMapping("/users/{id}/force-logout")
    public ResponseEntity<Map<String, String>> forceLogout(@PathVariable Long id) {
        // TODO: 实现强制下线逻辑
        // 当前返回成功但不实际执行任何操作
        Map<String, String> response = Map.of(
            "message", "强制下线请求已接受",
            "note", "此功能尚未完整实现，token不会被实际作废"
        );
        return ResponseEntity.ok(response);
    }
}
