package com.yangtze.bankwarning.ai.domain.po;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiChatMessagePO {
    private Long id;
    private String sessionId;
    private String role;
    private String content;
    private String contextText;
    private Long userId;
    private LocalDateTime createdAt;
}
