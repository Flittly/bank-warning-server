package com.yangtze.bankwarning.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * 允许的前端来源，逗号分隔。支持 Spring 的 origin pattern 语法：
     * <ul>
     *   <li>精确值：{@code http://localhost:5173}</li>
     *   <li>端口通配：{@code http://localhost:[*]} —— 匹配本机任意端口的 dev server，
     *       前端换端口（5173/5174/5175…）时无需再改代码或配置</li>
     * </ul>
     * ⚠ 生产部署务必用环境变量 {@code APP_CORS_ALLOWED_ORIGINS} 覆盖成精确域名。
     */
    @Value("${app.cors.allowed-origins:http://localhost:[*],http://127.0.0.1:[*]}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 必须用 allowedOriginPatterns 而不是 setAllowedOrigins：
        // 1) setAllowedOrigins 只接受字面量精确匹配（通配符仅 "*" 且不能与凭证共存），
        //    所以 http://localhost:[*] 会原样当成字符串去比，永远匹配不上；
        // 2) setAllowCredentials(true) 与 setAllowedOrigins("*") 互斥，Spring 会直接抛异常；
        // 3) allowedOriginPatterns 命中后回显的是**具体 origin**而非 "*"，因此与
        //    allowCredentials(true) 共存是安全的（这是 Spring 官方推荐的写法）。
        // 另外这里做了 trim + 去空：否则配置里写成 "a, b"（逗号后带空格）会静默失配。
        configuration.setAllowedOriginPatterns(
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList()
        );
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsFilter(source);
    }
}
