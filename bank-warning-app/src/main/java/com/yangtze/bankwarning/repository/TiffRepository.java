package com.yangtze.bankwarning.repository;

import com.yangtze.bankwarning.repository.support.AbstractJdbcRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class TiffRepository extends AbstractJdbcRepository {

    public TiffRepository(JdbcClient jdbcClient) {
        super(jdbcClient);
    }

    public List<Map<String, Object>> listAll() {
        return queryList(
                "SELECT id, tiff_key, region_code, year, timepoint, min_x, min_y, max_x, max_y, created_at, updated_at FROM tiff_bounds ORDER BY tiff_key",
                Map.of()
        );
    }

    public Map<String, Object> getByTiffKey(String tiffKey) {
        return queryOne(
                "SELECT id, tiff_key, region_code, year, timepoint, min_x, min_y, max_x, max_y, created_at, updated_at FROM tiff_bounds WHERE tiff_key = :tiffKey",
                params("tiffKey", tiffKey)
        );
    }
}
