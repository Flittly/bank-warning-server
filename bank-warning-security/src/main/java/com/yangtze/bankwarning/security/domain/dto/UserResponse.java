package com.yangtze.bankwarning.security.domain.dto;

import lombok.Data;

@Data
public class UserResponse {
    private Long id;
    private String username;
    private String phone;
    private String email;
    private String realName;
    private String avatar;
    private String role;
    private String status;
}
