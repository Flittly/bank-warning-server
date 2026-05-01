package com.example.demo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动类
 * 这是整个应用的入口点
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
        
        System.out.println("===========================================");
        System.out.println("  多模块示例应用启动成功！");
        System.out.println("  访问 http://localhost:8080/api/users");
        System.out.println("===========================================");
    }
}
