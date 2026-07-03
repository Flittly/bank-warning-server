package com.yangtze.bankwarning.domain.po;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiChatSessionPO {
    private Long id;
    private Long userId;
    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
