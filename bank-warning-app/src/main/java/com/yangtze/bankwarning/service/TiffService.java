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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TiffService {

    private static final Logger log = LoggerFactory.getLogger(TiffService.class);

    /**
     * DEM 预览可选的配色名，与模型服务 {@code model/demPreview.py} 的 {@code RAMPS} 一一对应。
     * 加配色要两边一起加，否则这边会把新名字当未知值回落到灰度。
     */
    private static final List<String> PREVIEW_RAMPS = List.of("gray", "hypsometric");

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
     * 取 DEM 预览图（高程配色 + 红色 nodata），由模型服务现场渲染。
     *
     * <p>图片本体直接透传字节，不落盘、不缓存到 Java 侧：模型服务已按
     * 「路径 + mtime + 文件大小 + 配色」做了磁盘缓存，这里再加一层只会出现两边不一致。
     *
     * <p>模型服务把地理范围与色标放在 {@code X-Dem-*} 响应头里，这里原样转给前端 ——
     * 前端要用这几项才能把图贴到正确位置、画出图例。
     *
     * @param tiffKey DEM 的 {@code tiff_key}
     * @param maxSize 输出长边像素上限，null/0 表示用服务端默认值（1536）
     * @param ramp 配色名（{@code gray} / {@code hypsometric}）；不认识的名字**回落到默认灰度**
     *             而不是报错 —— 它只影响观感，且响应头 {@code X-Dem-Ramp} 会回显实际用的配色，
     *             前端据它画图例，不会出现「图是灰的、图例是彩的」。
     */
    public ResponseEntity<byte[]> fetchPreview(String tiffKey, Integer maxSize, String ramp) {
        if (tiffKey == null || tiffKey.isBlank()) {
            throw new IllegalArgumentException("tiff_key is required");
        }

        String palette = normalizeRamp(ramp);

        // 必须构造 URI 对象再交给 RestTemplate：传 String 会被当成 URI 模板「再编码一次」，
        // tiff_key 里的 "/" → "%2F" → "%252F"，模型侧解码一次后拿到 "tiff%2FYZ%2F..." 这种
        // 并不存在的路径，直接 404（且会白等一轮 RustFS 回源的 10+ 秒）。
        URI uri = buildModelServiceUri(modelServiceBaseUrl, "/v0/dem/preview", tiffKey, maxSize, palette);

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
            // 绝不能给这张图加强缓存：URL 只由 tiff_key 与 ramp 决定，而「同一份 DEM 的内容」
            // 会随重新切片、换配色、nodata 改透明而变化。上一版是 maxAge(1h).cachePublic()，
            // 结果是：用户在旧进程上点过一次「分层设色」，浏览器就把灰度图存到了
            // ?tiff_key=X&ramp=hypsometric 这个 URL 下，重启后端也没用 —— 点完 Segmented
            // 拿回的还是缓存的 X-Dem-Ramp: gray，选中态立刻弹回灰度，表现为「点了没反应」。
            // 现场渲染的图本来就该每次取新的；真要省算力有模型侧的磁盘缓存兜着（按路径+mtime+
            // 文件大小+配色做键），HTTP 这一层不需要也不应该再拦一道。
            outHeaders.setCacheControl(CacheControl.noStore());
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
        return buildModelServiceUri(baseUrl, path, tiffKey, maxSize, null);
    }

    /**
     * 同上，额外带上配色名。{@code ramp} 为空时**不写进查询参数** ——
     * 否则模型服务的地址会平白多一个 {@code ramp=}，也让既有断言（URI 逐字比对）失效。
     */
    static URI buildModelServiceUri(
            String baseUrl, String path, String tiffKey, Integer maxSize, String ramp) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tiff_key", tiffKey);
        if (maxSize != null && maxSize > 0) {
            params.put("max_size", maxSize);
        }
        if (ramp != null && !ramp.isBlank()) {
            params.put("ramp", ramp);
        }
        return buildModelServiceUri(baseUrl, path, params);
    }

    /** 只放行前端会发的那两个配色名；其余一律当没传（回落默认灰度）。 */
    static String normalizeRamp(String ramp) {
        if (ramp == null || ramp.isBlank()) {
            return null;
        }
        String value = ramp.trim().toLowerCase(Locale.ROOT);
        if (PREVIEW_RAMPS.contains(value)) {
            return value;
        }
        log.warn("[dem-preview] 未知配色 {}，按默认灰度处理", ramp);
        return null;
    }

    /** 同上的通用版：查询参数按键值对给，值为 null 的不追加。同样必须返回 {@link URI}。 */
    static URI buildModelServiceUri(String baseUrl, String path, Map<String, ?> params) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base).path(path);
        params.forEach((key, value) -> {
            if (value != null) {
                builder.queryParam(key, value);
            }
        });
        return builder.build().encode(StandardCharsets.UTF_8).toUri();
    }

    // ==================== 三维地形（Mapbox terrain 的 Terrarium 瓦片） ====================
    //
    // 地形瓦片不能像天地图那样放进 nginx 的静态目录：DEM 是**按用户隔离**的（tiff_bounds.user_id），
    // 而 /tiles/ 没有鉴权。所以每张瓦片都走这里 —— 先判归属，再转发模型服务。
    // 代价是每张瓦片多一次单行查询 + 一次内网转发，一份 DEM 首屏也就一两百张，可以接受。

    /** 金字塔指纹：模型服务生成的 16 位十六进制。它会被拼进转发 URL，必须先验格式。 */
    private static final Pattern TERRAIN_FP = Pattern.compile("^[0-9a-f]{16}$");

    /** 指纹在 URL 里 ⇒ 源文件一换 URL 就换 ⇒ 可以让浏览器放心缓存一年。 */
    static final String TERRAIN_TILE_CACHE_CONTROL = "private, max-age=31536000, immutable";

    /**
     * 取出一份 DEM 的边界行，并确认**当前登录用户**有权访问它。
     *
     * <p>权限判定用 {@link SecurityUtils#getDataScope()} 而不是 {@code getCurrentUserIdForDataFilter()}：
     * 后者的 {@code null} 同时表示「管理员」与「读不到身份」，是 fail-open；
     * 这里 fail-closed —— 读不到身份一律拒绝。
     *
     * <p>无权访问与不存在返回**同一个异常**（→ 404）：不向调用方泄露「别人有这份 DEM」。
     */
    TiffBoundsPO requireAccessibleTiff(String tiffKey) {
        if (tiffKey == null || tiffKey.isBlank()) {
            throw new IllegalArgumentException("tiff_key is required");
        }
        SecurityUtils.DataScope scope = SecurityUtils.getDataScope();
        if (!scope.authenticated()) {
            throw new IllegalArgumentException("无法确认当前用户身份，拒绝访问 DEM：" + tiffKey);
        }
        TiffBoundsPO row = tiffBoundsMapper.selectByTiffKey(tiffKey);
        if (row == null) {
            throw new IllegalArgumentException("DEM 不存在或无权访问：" + tiffKey);
        }
        if (!scope.seeAll() && (scope.userId() == null || !scope.userId().equals(row.getUserId()))) {
            throw new IllegalArgumentException("DEM 不存在或无权访问：" + tiffKey);
        }
        return row;
    }

    /** 三维地形状态（顺手让模型服务做过期检查）。 */
    public Map<String, Object> fetchTerrainStatus(String tiffKey) {
        requireAccessibleTiff(tiffKey);
        return callModelServiceJson(buildModelServiceUri(modelServiceBaseUrl, "/v0/dem/terrain/status", tiffKey, null), false);
    }

    /** 开始（或确认）切片。幂等：已就绪且未过期时模型服务什么都不做。 */
    public Map<String, Object> requestTerrainBuild(String tiffKey) {
        requireAccessibleTiff(tiffKey);
        return callModelServiceJson(buildModelServiceUri(modelServiceBaseUrl, "/v0/dem/terrain/build", tiffKey, null), true);
    }

    private Map<String, Object> callModelServiceJson(URI uri, boolean post) {
        try {
            ResponseEntity<Map> upstream = post
                    ? restTemplate.postForEntity(uri, null, Map.class)
                    : restTemplate.getForEntity(uri, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = upstream.getBody();
            return body == null ? new LinkedHashMap<>() : body;
        } catch (HttpStatusCodeException exception) {
            int status = exception.getStatusCode().value();
            String detail = shorten(exception.getResponseBodyAsString());
            log.warn("[dem-terrain] 模型服务返回错误, uri={}, status={}, detail={}", uri.getPath(), status, detail);
            if (status == 404 || status == 400) {
                throw new IllegalArgumentException("DEM 三维地形请求被拒绝：" + detail);
            }
            throw new IllegalStateException("DEM 三维地形生成失败：" + detail, exception);
        }
    }

    /**
     * 转发一张地形瓦片。
     *
     * <p>ETag 由「指纹 + 层级行列」拼出，不读文件内容：瓦片不可变，指纹一换 URL 就换。
     * 命中 {@code If-None-Match} 直接回 304，连模型服务都不用去。
     *
     * <p>模型服务回 404（走廊以外的空白瓦片）原样透传成 404 —— Mapbox 把「没有瓦片」
     * 当成 0 米，这正是裙边设计依赖的约定。
     */
    public ResponseEntity<byte[]> fetchTerrainTile(
            String tiffKey, String fingerprint, int z, int x, int y, String ifNoneMatch) {
        if (fingerprint == null || !TERRAIN_FP.matcher(fingerprint).matches()
                || z < 0 || z > 24 || x < 0 || x >= (1 << z) || y < 0 || y >= (1 << z)) {
            throw new IllegalArgumentException("地形瓦片参数非法");
        }
        requireAccessibleTiff(tiffKey);

        String etag = terrainEtag(fingerprint, z, x, y);
        if (etag.equals(ifNoneMatch == null ? null : ifNoneMatch.trim())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, TERRAIN_TILE_CACHE_CONTROL)
                    .build();
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tiff_key", tiffKey);
        params.put("fp", fingerprint);
        params.put("z", z);
        params.put("x", x);
        params.put("y", y);
        URI uri = buildModelServiceUri(modelServiceBaseUrl, "/v0/dem/terrain/tile", params);
        try {
            ResponseEntity<byte[]> upstream = restTemplate.getForEntity(uri, byte[].class);
            byte[] body = upstream.getBody();
            if (body == null || body.length == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, TERRAIN_TILE_CACHE_CONTROL)
                    .body(body);
        } catch (HttpStatusCodeException exception) {
            // 404 不能带长缓存头：源文件更新后同一 URL（指纹）下不该再出现，但别让中间任何一层记住它
            return ResponseEntity.status(exception.getStatusCode())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        }
    }

    static String terrainEtag(String fingerprint, int z, int x, int y) {
        return "\"terrain-" + fingerprint + '-' + z + '-' + x + '-' + y + "\"";
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
        // 三维地形是否已就绪（null = 从未切过）。前端据此决定「直接挂地形」还是「先生成」。
        map.put("terrain_status", po.getTerrainStatus());
        return map;
    }
}
