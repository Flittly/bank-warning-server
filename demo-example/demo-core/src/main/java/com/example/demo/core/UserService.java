package com.example.demo.core;

import java.util.List;

/**
 * 接口定义：用户服务
 * 只定义"做什么"，不定义"怎么做"
 */
public interface UserService {
    
    /**
     * 根据 ID 查询用户
     */
    User findById(String id);
    
    /**
     * 查询所有用户
     */
    List<User> findAll();
    
    /**
     * 保存用户
     */
    void save(User user);
}
