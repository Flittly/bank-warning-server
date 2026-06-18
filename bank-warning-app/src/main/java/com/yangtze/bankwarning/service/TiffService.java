package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.TiffBoundsPO;
import com.yangtze.bankwarning.mapper.TiffBoundsMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TiffService {

    private static final Logger log = LoggerFactory.getLogger(TiffService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final TiffBoundsMapper tiffBoundsMapper;

    @Value("${app.model-service.base-url:http://localhost:8088}")
    private String modelServiceBaseUrl;

    public TiffService(TiffBoundsMapper tiffBoundsMapper) {
        this.tiffBoundsMapper = tiffBoundsMapper;
    }

    public List<Map<String, Object>> listTiffs() {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return tiffBoundsMapper.selectAll(userId).stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> uploadTiff(MultipartFile file, String segment, String year, String timepoint) {
        try {
            String url = modelServiceBaseUrl + "/api/v1/tiff/register";

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            });
            body.add("segment", segment);
            body.add("year", year);
            body.add("timepoint", timepoint);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            log.info("[tiff-upload] sending to Python, segment={}, year={}, timepoint={}, filename={}",
                    segment, year, timepoint, file.getOriginalFilename());

            ResponseEntity<Map> response = restTemplate.postForEntity(url, requestEntity, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = response.getBody();

            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                log.info("[tiff-upload] success, tiff_key={}", result.get("tiff_key"));
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("success", true);
                output.put("tiff_key", result.get("tiff_key"));
                output.put("file_name", result.get("file_name"));
                output.put("bounds", Map.of(
                        "min_x", result.get("min_x"),
                        "min_y", result.get("min_y"),
                        "max_x", result.get("max_x"),
                        "max_y", result.get("max_y")
                ));
                output.put("rustfs_synced", result.getOrDefault("rustfs_synced", false));
                return output;
            }

            throw new IllegalStateException("Python service returned unexpected response: " + result);
        } catch (Exception exception) {
            log.error("[tiff-upload] failed, segment={}, year={}, timepoint={}, error={}",
                    segment, year, timepoint, exception.getMessage(), exception);
            throw new IllegalStateException("Failed to upload tiff: " + exception.getMessage(), exception);
        }
    }

    public Map<String, Object> deleteTiff(String tiffKey) {
        try {
            String url = modelServiceBaseUrl + "/api/v1/tiff/register?tiff_key=" +
                    java.net.URLEncoder.encode(tiffKey, "UTF-8");

            log.info("[tiff-delete] calling Python, tiff_key={}", tiffKey);
            restTemplate.delete(url);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("success", true);
            output.put("tiff_key", tiffKey);
            output.put("deleted", true);
            return output;
        } catch (Exception exception) {
            log.error("[tiff-delete] failed, tiff_key={}, error={}", tiffKey, exception.getMessage(), exception);
            throw new IllegalStateException("Failed to delete tiff: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> toMap(TiffBoundsPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("tiff_key", po.getTiffKey());
        map.put("region_code", po.getRegionCode());
        map.put("year", po.getYear());
        map.put("timepoint", po.getTimepoint());
        map.put("min_x", po.getMinX());
        map.put("min_y", po.getMinY());
        map.put("max_x", po.getMaxX());
        map.put("max_y", po.getMaxY());
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }
}
