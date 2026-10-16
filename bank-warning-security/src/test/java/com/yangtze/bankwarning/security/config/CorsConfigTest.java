package com.yangtze.bankwarning.security.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 契约。
 *
 * <p>盯住一个容易被静默破坏的点：**非简单响应头必须显式暴露**。报告导出要按
 * {@code Content-Disposition} 决定下载名（按经纬度分幅的报告导出的压缩包名带中文，
 * 且可能带 {@code filename*=UTF-8''}），前端还要读 {@code X-Section-Csv-Count}
 * 提示包里带了几个分区 CSV。跨域部署时若这里被删掉，前端只会「下载成一个
 * 名字不对的 docx」，不报任何错。
 */
@DisplayName("CORS 配置")
class CorsConfigTest {

    private CorsConfig configWithOrigins(String origins) {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        return config;
    }

    @Test
    @DisplayName("暴露报告导出所需的响应头")
    void exposesDownloadHeaders() {
        CorsConfiguration configuration = configWithOrigins("http://localhost:[*]").buildConfiguration();

        List<String> exposed = configuration.getExposedHeaders();
        assertTrue(exposed != null && exposed.contains("Content-Disposition"), String.valueOf(exposed));
        assertTrue(exposed.contains("X-Section-Csv-Count"), String.valueOf(exposed));
    }

    @Test
    @DisplayName("origin 列表 trim + 去空，逗号后带空格不会静默失配")
    void trimsOriginList() {
        CorsConfiguration configuration =
                configWithOrigins(" http://localhost:5173 , , https://example.com ").buildConfiguration();

        assertEquals(List.of("http://localhost:5173", "https://example.com"),
                configuration.getAllowedOriginPatterns());
    }

    @Test
    @DisplayName("带凭证的跨域必须用 originPatterns 且不能是通配 *")
    void credentialsWithOriginPatterns() {
        CorsConfiguration configuration = configWithOrigins("http://localhost:[*]").buildConfiguration();

        assertEquals(Boolean.TRUE, configuration.getAllowCredentials());
        assertTrue(configuration.getAllowedOrigins() == null
                        || !configuration.getAllowedOrigins().contains("*"),
                "不能对 * 开放凭证");
    }
}
