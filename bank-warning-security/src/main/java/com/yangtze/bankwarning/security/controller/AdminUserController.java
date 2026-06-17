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

    @PostMapping("/users/{id}/force-logout")
    public ResponseEntity<Map<String, String>> forceLogout(@PathVariable Long id) {
        // 这里需要实现强制下线逻辑
        // 可以通过Redis存储黑名单实现
        Map<String, String> response = Map.of("message", "强制下线成功");
        return ResponseEntity.ok(response);
    }
}
