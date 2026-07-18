package com.yangtze.bankwarning.ai.domain.po;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WorkbenchConfigPO {
    private Long id;
    private Long userId;
    private String title;
    private String configJson;
    private LocalDateTime createdAt;
}
