package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.TiffBoundsPO;
import com.yangtze.bankwarning.mapper.TiffBoundsMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
            // 同 fetchPreview：必须传 URI 对象，否则 tiff_key 被二次编码；删除接口之前在
            // Python 侧靠 unquote 兜住了这个错误，但那是巧合，不是设计。
            URI uri = buildModelServiceUri(modelServiceBaseUrl, "/api/v1/tiff/register", tiffKey, null);

            log.info("[tiff-delete] calling Python, tiff_key={}", tiffKey);
            restTemplate.delete(uri);

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

    /**
     * 取 DEM 预览图（灰度高程 + 红色 nodata），由模型服务现场渲染。
     *
     * <p>图片本体直接透传字节，不落盘、不缓存到 Java 侧：模型服务已按
     * 「路径 + mtime + 文件大小」做了磁盘缓存，这里再加一层只会出现两边不一致。
     *
     * <p>模型服务把地理范围与色标放在 {@code X-Dem-*} 响应头里，这里原样转给前端 ——
     * 前端要用这几项才能把图贴到正确位置、画出图例。
     *
     * @param tiffKey DEM 的 {@code tiff_key}
     * @param maxSize 输出长边像素上限，null/0 表示用服务端默认值（1536）
     */
    public ResponseEntity<byte[]> fetchPreview(String tiffKey, Integer maxSize) {
        if (tiffKey == null || tiffKey.isBlank()) {
            throw new IllegalArgumentException("tiff_key is required");
        }

        // 必须构造 URI 对象再交给 RestTemplate：传 String 会被当成 URI 模板「再编码一次」，
        // tiff_key 里的 "/" → "%2F" → "%252F"，模型侧解码一次后拿到 "tiff%2FYZ%2F..." 这种
        // 并不存在的路径，直接 404（且会白等一轮 RustFS 回源的 10+ 秒）。
        URI uri = buildModelServiceUri(modelServiceBaseUrl, "/v0/dem/preview", tiffKey, maxSize);

        long startedAt = System.currentTimeMillis();
        log.info("[dem-preview] 请求模型服务生成预览, tiff_key={}, uri={}", tiffKey, uri);
        try {
            ResponseEntity<byte[]> upstream = restTemplate.getForEntity(uri, byte[].class);
            byte[] body = upstream.getBody();
            if (body == null || body.length == 0) {
                throw new IllegalStateException("模型服务返回了空的 DEM 预览图");
            }

            HttpHeaders outHeaders = new HttpHeaders();
            outHeaders.setContentType(MediaType.IMAGE_PNG);
            outHeaders.setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
            upstream.getHeaders().forEach((name, values) -> {
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith("x-dem-")) {
                    outHeaders.put(name, values);
                }
            });

            log.info("[dem-preview] 生成成功, tiff_key={}, bytes={}, elapsed={}ms",
                    tiffKey, body.length, System.currentTimeMillis() - startedAt);
            return new ResponseEntity<>(body, outHeaders, HttpStatus.OK);
        } catch (HttpStatusCodeException exception) {
            // 模型服务用 404 表示「文件不存在」、400 表示「打不开 / 不是单波段」，逐档对应回去；
            // 其余一律 500，避免把「参数错了」误报成「对象已被删除」。
            int status = exception.getStatusCode().value();
            String detail = exception.getResponseBodyAsString();
            log.warn("[dem-preview] 模型服务返回错误, tiff_key={}, status={}, detail={}", tiffKey, status, detail);
            if (status == 404) {
                throw new IllegalArgumentException("DEM 文件不存在：" + tiffKey);
            }
            if (status == 400) {
                throw new IllegalArgumentException("DEM 无法生成预览：" + shorten(detail));
            }
            throw new IllegalStateException("DEM 预览生成失败：" + shorten(detail), exception);
        }
    }

    /**
     * 构造指向模型服务的 URI（{@code tiff_key} 放在查询参数里）。
     *
     * <p><b>必须返回 {@link URI} 对象，不能返回 String</b>：{@code RestTemplate} 拿到 String
     * 时会把它当成 URI 模板「再编码一次」，手工编码出的 {@code %2F} 会变成 {@code %252F}；
     * 模型服务只解码一次，实际收到的 key 是 {@code tiff%2FYZ%2F2012%2F...}，按字面当路径去找
     * 必然 404（还会白等一轮 RustFS 回源的超时）。传 URI 对象走的是
     * {@code execute(URI, ...)}，不再做模板展开。
     */
    static URI buildModelServiceUri(String baseUrl, String path, String tiffKey, Integer maxSize) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base)
                .path(path)
                .queryParam("tiff_key", tiffKey);
        if (maxSize != null && maxSize > 0) {
            builder.queryParam("max_size", maxSize);
        }
        return builder.build().encode(StandardCharsets.UTF_8).toUri();
    }

    /** 截断上游错误体，避免把整段堆栈塞进前端提示里。 */
    private String shorten(String detail) {
        if (detail == null || detail.isBlank()) {
            return "模型服务未返回原因";
        }
        String singleLine = detail.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "…";
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
