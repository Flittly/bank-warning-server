package com.example.demo.app;

import com.example.demo.core.User;
import com.example.demo.core.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Web 控制器：提供 HTTP API
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    // 注入的是接口类型（定义在 core 模块）
    // Spring 会自动找到 service 模块中的 InMemoryUserService 实现
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 创建用户
     * POST /api/users
     */
    @PostMapping
    public User createUser(@RequestBody User user) {
        // 如果没有 ID，自动生成
        if (user.getId() == null || user.getId().isEmpty()) {
            user.setId(UUID.randomUUID().toString());
        }
        
        // 调用服务层保存（实际执行的是 InMemoryUserService.save）
        userService.save(user);
        
        return user;
    }

    /**
     * 查询所有用户
     * GET /api/users
     */
    @GetMapping
    public List<User> getAllUsers() {
        return userService.findAll();
    }

    /**
     * 根据 ID 查询用户
     * GET /api/users/{id}
     */
    @GetMapping("/{id}")
    public User getUserById(@PathVariable String id) {
        User user = userService.findById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在: " + id);
        }
        return user;
    }
}
