package com.yangtze.bankwarning.security.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度必须在3-50之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, message = "密码长度至少8位")
    private String password;

    @NotBlank(message = "手机号不能为空")
    private String phone;

    private String email;

    private String realName;
}
