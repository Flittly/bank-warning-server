package com.yangtze.bankwarning.security.domain.po;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserPO {
    private Long id;
    private String username;
    private String password;
    private String phone;
    private String email;
    private String realName;
    private String avatar;
    private String role;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
