package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BankPO {
    private Long id;
    private String bankId;
    private String bankName;
    private String regionCode;
    private String bankGeometry;
    private Boolean reversed;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
