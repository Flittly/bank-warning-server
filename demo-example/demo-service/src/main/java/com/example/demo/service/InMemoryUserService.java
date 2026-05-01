package com.example.demo.service;

import com.example.demo.core.User;
import com.example.demo.core.UserService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户服务的内存实现
 * 实现了 core 模块中定义的 UserService 接口
 */
@Service
public class InMemoryUserService implements UserService {
    
    // 用 Map 模拟数据库存储
    private final Map<String, User> userStore = new ConcurrentHashMap<>();

    @Override
    public User findById(String id) {
        return userStore.get(id);
    }

    @Override
    public List<User> findAll() {
        return new ArrayList<>(userStore.values());
    }

    @Override
    public void save(User user) {
        userStore.put(user.getId(), user);
        System.out.println("[Service] 保存用户: " + user);
    }
}
