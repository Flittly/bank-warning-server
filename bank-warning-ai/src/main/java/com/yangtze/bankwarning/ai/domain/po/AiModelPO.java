package com.yangtze.bankwarning.ai.domain.po;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiModelPO {
    private Long id;
    private String modelKey;
    private String label;
    private String apiKey;
    private String baseUrl;
    private String modelName;
    private Boolean isDefault;
    private LocalDateTime createdAt;
}
