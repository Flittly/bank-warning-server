package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TiffBoundsPO {
    private Long id;
    private String tiffKey;
    private String regionCode;
    private String year;
    private String timepoint;
    private Double minX;
    private Double minY;
    private Double maxX;
    private Double maxY;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
