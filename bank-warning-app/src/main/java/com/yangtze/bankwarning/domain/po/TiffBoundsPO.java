package com.yangtze.bankwarning.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TiffBoundsPO {
    private Long id;
    private Long userId;
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

    // —— 三维地形（模型服务切的 Terrarium 瓦片金字塔）——
    // 列由模型服务启动时幂等补上；列还没加上时 SELECT * 查不到，这几个字段就是 null。
    /** pending / building / ready / failed；null = 从未切过 */
    private String terrainStatus;
    /** 金字塔指纹，同时是磁盘目录名与瓦片 URL 的一部分 */
    private String terrainFp;
    private Integer terrainMinzoom;
    private Integer terrainMaxzoom;
}
