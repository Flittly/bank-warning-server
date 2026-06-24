package com.yangtze.bankwarning.config;

import com.yangtze.bankwarning.security.mapper.UserMapper;
import com.yangtze.bankwarning.security.domain.po.UserPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DefaultAdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminInitializer.class);
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public DefaultAdminInitializer(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userMapper.findByUsername("admin") == null) {
            UserPO admin = new UserPO();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setPhone("13800138000");
            admin.setEmail("admin@yangtze.com");
            admin.setRealName("系统管理员");
            admin.setRole("ADMIN");
            admin.setStatus("ACTIVE");
            userMapper.insert(admin);
            log.info("默认管理员用户已创建（admin/admin123）");
        } else {
            log.info("管理员用户已存在，跳过创建");
        }
    }
}
