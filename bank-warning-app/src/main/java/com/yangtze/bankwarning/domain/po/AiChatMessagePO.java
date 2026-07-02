package com.yangtze.bankwarning.domain.po;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiChatMessagePO {
    private Long id;
    private String sessionId;
    private String role;        // 'user' 或 'assistant'
    private String content;
    private String contextText; // 上下文标签（如 "📄 report_123.md"）
    private Long userId;
    private LocalDateTime createdAt;
}
