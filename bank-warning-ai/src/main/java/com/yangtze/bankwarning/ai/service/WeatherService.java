package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 和风天气 API 调用服务
 * 文档：https://dev.qweather.com/docs/api/
 *
 * 使用的端点：
 * - /weather/now       实时天气
 * - /weather/{d}d      多日预报（3d/7d/10d/15d/30d）
 * - /weather/24h       24小时逐小时预报
 * - /warning/now       当前生效的天气预警
 * - /geo/v2/city/lookup  经纬度反查城市
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String geoUrl;
    private final long cacheTtlMillis;

    private final Map<String, CacheEntry<String>> cache = new ConcurrentHashMap<>();

    public WeatherService(ObjectMapper objectMapper,
                          @Value("${qweather.api-key:}") String apiKey,
                          @Value("${qweather.base-url:https://devapi.qweather.com/v7}") String baseUrl,
                          @Value("${qweather.geo-url:https://geoapi.qweather.com/v2}") String geoUrl,
                          @Value("${qweather.cache-ttl-minutes:30}") int cacheTtlMinutes) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.geoUrl = geoUrl;
        this.cacheTtlMillis = Duration.ofMinutes(cacheTtlMinutes).toMillis();
    }

    /**
     * 获取完整天气预报（实时 + 多日预报 + 预警 + 城市名）
     * 返回格式化好的中文文本，方便 LLM 直接阅读
     */
    public String getFullForecast(double lng, double lat, int days) {
        if (apiKey == null || apiKey.isBlank()) {
            return "天气查询不可用：未配置和风天气 API Key（QWEATHER_API_KEY）。请跳过天气分析。";
        }
        if (days < 1 || days > 7) {
            return "天气查询失败：days 参数必须在 1-7 之间（当前：" + days + "）";
        }

        String cacheKey = String.format("forecast:%.4f,%.4f:%d", lng, lat, days);
        String cached = getFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            String location = String.format("%.2f,%.2f", lng, lat);
            String cityName = lookupCityName(lng, lat);
            String current = getCurrentWeather(location);
            String forecast = getDailyForecast(location, days);
            String warning = fetchActiveWarning(location);

            String result = formatFullForecast(cityName, current, forecast, warning, days);
            putToCache(cacheKey, result);
            return result;
        } catch (Exception e) {
            log.error("[weather] failed to fetch forecast for ({}, {}): {}", lng, lat, e.getMessage());
            return "天气查询失败：" + e.getMessage() + "。请基于风险数据继续生成报告。";
        }
    }

    /**
     * 获取当前生效的天气预警
     */
    public String getActiveWarning(double lng, double lat) {
        if (apiKey == null || apiKey.isBlank()) {
            return "天气预警查询不可用：未配置和风天气 API Key。";
        }

        String cacheKey = String.format("warning:%.4f,%.4f", lng, lat);
        String cached = getFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            String location = String.format("%.2f,%.2f", lng, lat);
            String result = fetchActiveWarning(location);
            putToCache(cacheKey, result);
            return result;
        } catch (Exception e) {
            log.error("[weather] failed to fetch warnings: {}", e.getMessage());
            return "天气预警查询失败：" + e.getMessage();
        }
    }

    private String fetchActiveWarning(String location) throws Exception {
        String url = baseUrl + "/warning/now?location=" + location + "&key=" + apiKey;
        JsonNode root = fetchJson(url);

        if (!"200".equals(root.path("code").asText())) {
            String msg = root.path("code").asText("未知错误");
            return "天气预警查询失败，错误码：" + msg;
        }

        JsonNode warningArr = root.path("warning");
        if (!warningArr.isArray() || warningArr.isEmpty()) {
            return "当前无生效的天气预警。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前生效 ").append(warningArr.size()).append(" 条预警：\n");
        for (JsonNode w : warningArr) {
            String type = w.path("type").asText("未知");
            String level = w.path("level").asText("未知");
            String title = w.path("title").asText("");
            String text = w.path("text").asText("");
            sb.append("- [").append(type).append(" / ").append(level).append("] ")
              .append(title).append("\n")
              .append("  ").append(text).append("\n");
        }
        return sb.toString();
    }

    // ==================== 内部方法 ====================

    private String lookupCityName(double lng, double lat) throws Exception {
        String url = geoUrl + "/city/lookup?location=" + String.format("%.2f,%.2f", lng, lat) + "&key=" + apiKey;
        JsonNode root = fetchJson(url);
        if (!"200".equals(root.path("code").asText())) {
            return String.format("经纬度 (%.4f, %.4f) 附近", lng, lat);
        }
        JsonNode locations = root.path("location");
        if (!locations.isArray() || locations.isEmpty()) {
            return String.format("经纬度 (%.4f, %.4f) 附近", lng, lat);
        }
        JsonNode first = locations.get(0);
        String adm1 = first.path("adm1").asText("");
        String adm2 = first.path("adm2").asText("");
        String name = first.path("name").asText("");
        if (adm1.isBlank() && name.isBlank()) {
            return String.format("经纬度 (%.4f, %.4f) 附近", lng, lat);
        }
        return adm1 + " " + adm2 + " " + name;
    }

    private String getCurrentWeather(String location) throws Exception {
        String url = baseUrl + "/weather/now?location=" + location + "&key=" + apiKey;
        JsonNode root = fetchJson(url);
        if (!"200".equals(root.path("code").asText())) {
            throw new RuntimeException("实时天气 API 返回错误码 " + root.path("code").asText());
        }
        JsonNode now = root.path("now");
        return String.format("温度 %s℃，体感 %s℃，%s，风力 %s 级（%s），湿度 %s%%，当前小时降水量 %s mm",
                now.path("temp").asText("?"),
                now.path("feelsLike").asText("?"),
                now.path("text").asText("未知"),
                now.path("windScale").asText("?"),
                now.path("windDir").asText("?"),
                now.path("humidity").asText("?"),
                now.path("precip").asText("0.0"));
    }

    private String getDailyForecast(String location, int days) throws Exception {
        // 和风天气的预报端点：3d / 7d
        String endpoint = days <= 3 ? "/weather/3d" : "/weather/7d";
        String url = baseUrl + endpoint + "?location=" + location + "&key=" + apiKey;
        JsonNode root = fetchJson(url);
        if (!"200".equals(root.path("code").asText())) {
            throw new RuntimeException("预报 API 返回错误码 " + root.path("code").asText());
        }

        JsonNode daily = root.path("daily");
        if (!daily.isArray() || daily.isEmpty()) {
            return "无预报数据";
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(days, daily.size());
        double totalPrecip = 0;
        boolean hasHeavyRain = false;
        boolean hasModerateRain = false;

        for (int i = 0; i < limit; i++) {
            JsonNode d = daily.get(i);
            String date = d.path("fxDate").asText("?");
            String textDay = d.path("textDay").asText("?");
            String textNight = d.path("textNight").asText("");
            String tempMax = d.path("tempMax").asText("?");
            String tempMin = d.path("tempMin").asText("?");
            String precip = d.path("precip").asText("0.0");
            String windDir = d.path("windDirDay").asText("?");
            String windScale = d.path("windScaleDay").asText("?");

            double precipVal = parseDoubleSafe(precip);
            totalPrecip += precipVal;

            String precipStr = precipVal > 0 ? String.format("，降水 %s mm", precip) : "";
            String dayLabel = i == 0 ? "今日" : (i == 1 ? "明日" : "第" + (i + 1) + "日");
            sb.append(String.format("  %s（%s）：%s转%s，气温 %s~%s℃%s，%s %s 级\n",
                    dayLabel, date, textDay, textNight, tempMin, tempMax, precipStr, windDir, windScale));

            if (precipVal >= 50) hasHeavyRain = true;
            else if (precipVal >= 10) hasModerateRain = true;
        }

        sb.insert(0, String.format("未来 %d 天累计降水约 %.1f mm", days, totalPrecip));
        if (hasHeavyRain) sb.append("\n⚠️ 预警：未来几天内存在单日降水 ≥ 50 mm 的暴雨过程");
        else if (hasModerateRain) sb.append("\n注意：未来几天内存在单日降水 ≥ 10 mm 的中雨以上过程");

        return sb.toString();
    }

    private String formatFullForecast(String cityName, String current, String forecast, String warning, int days) {
        StringBuilder sb = new StringBuilder();
        sb.append("【研究区天气分析】\n");
        sb.append("位置：").append(cityName).append("\n\n");
        sb.append("【实时天气】\n").append(current).append("\n\n");
        sb.append("【未来").append(days).append("天预报】\n").append(forecast).append("\n\n");
        if (warning != null && !warning.isBlank() && !warning.startsWith("当前无")) {
            sb.append("【天气预警】\n").append(warning).append("\n");
        }
        return sb.toString();
    }

    private JsonNode fetchJson(String url) throws Exception {
        log.info("[weather] GET {}", url.replace(apiKey, "***"));
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("HTTP 状态码 " + response.getStatusCode());
        }
        return objectMapper.readTree(response.getBody());
    }

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    // ==================== 简易缓存 ====================

    private String getFromCache(String key) {
        CacheEntry<String> entry = cache.get(key);
        if (entry == null) return null;
        if (Instant.now().toEpochMilli() - entry.timestamp > cacheTtlMillis) {
            cache.remove(key);
            return null;
        }
        return entry.value;
    }

    private void putToCache(String key, String value) {
        cache.put(key, new CacheEntry<>(value, Instant.now().toEpochMilli()));
    }

    private static class CacheEntry<V> {
        final V value;
        final long timestamp;
        CacheEntry(V value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }
}
