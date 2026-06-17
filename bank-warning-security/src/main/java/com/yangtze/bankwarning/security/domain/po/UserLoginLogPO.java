package com.yangtze.bankwarning.security.domain.po;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserLoginLogPO {
    private Long id;
    private Long userId;
    private String loginType;
    private String loginIp;
    private String loginDevice;
    private LocalDateTime loginTime;
    private String status;
}
