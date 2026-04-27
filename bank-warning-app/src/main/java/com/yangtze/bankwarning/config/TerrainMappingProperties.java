package com.yangtze.bankwarning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Kafka 地形数据配置
 * 
 * 地形文件路径从数据库 banks.terrain_key 读取
 * 此配置只保留 bucket 名称，用于传递给 Python Worker
 */
@Component
@ConfigurationProperties(prefix = "app.kafka")
public class TerrainMappingProperties {

    // RustFS bucket 名称（传递给 Python Worker）
    private String terrainBucket = "yangtze-bank-warning-system";

    public String getTerrainBucket() {
        return terrainBucket;
    }

    public void setTerrainBucket(String terrainBucket) {
        this.terrainBucket = terrainBucket;
    }
}
