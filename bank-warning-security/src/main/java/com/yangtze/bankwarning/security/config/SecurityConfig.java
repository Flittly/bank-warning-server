package com.yangtze.bankwarning.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import com.yangtze.bankwarning.security.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 浏览器跨源预检（OPTIONS）按规范**不携带 Authorization 头**，若不显式放行，
                // 会被下面的 anyRequest().authenticated() 直接拒掉（403），导致所有跨源
                // 写操作在预检阶段就失败 —— 表现为「GET 正常、POST 全挂」。
                // 放行 OPTIONS 本身无副作用（不改变服务端状态），且请求随后仍会经过
                // CorsConfig 的 CorsFilter：白名单外的 origin 依旧被 403 拦下。
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // SSE / 异步响应收尾时，容器会带着 DispatcherType.ASYNC 再走一遍过滤器链。
                // 此时 STATELESS 的安全上下文已在初始 REQUEST 结束时清空，若不放行，
                // AuthorizationFilter 会判 403；而响应已经提交，异常无法再写错误页，
                // 结果是 Tomcat 直接断连 —— 表现为「内容收全了，但 chunked 流没有结束块」。
                // 放行 ASYNC 不会削弱鉴权：ASYNC 分发只是已授权请求的延续，它本身不改变状态。
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                .requestMatchers("/v0/auth/**", "/v0/bank/ai/viz-output/**").permitAll()
                .requestMatchers("/v0/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, 
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 角色层级：超级管理员 > 管理员 > 普通用户。
     * 配合各接口的 hasAnyRole('SUPER_ADMIN','ADMIN') 使用（JWT 过滤器直接注入单一
     * ROLE_ 权限，不走 GrantedAuthoritiesMapper，因此方法级注解采用显式 hasAnyRole）。
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_SUPER_ADMIN > ROLE_ADMIN > ROLE_USER");
    }
}
