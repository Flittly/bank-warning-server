package com.yangtze.bankwarning.security.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails) {
            return ((CustomUserDetails) authentication.getPrincipal()).getUserId();
        }
        return null;
    }

    public static String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            return authentication.getName();
        }
        return null;
    }

    /** 获取当前用户的角色（去掉 ROLE_ 前缀，例如 SUPER_ADMIN / ADMIN / USER） */
    public static String getCurrentUserRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(auth -> auth.startsWith("ROLE_"))
                .map(auth -> auth.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取当前用户ID用于数据过滤。
     * 管理员（ADMIN / SUPER_ADMIN）返回 null —— SQL 中 #{userId} IS NULL 即看全部；
     * 普通用户返回自己的 ID，只看自己的数据。
     *
     * 注意：这里必须同时判 ROLE_ADMIN 与 ROLE_SUPER_ADMIN。
     * UserDetailsServiceImpl 发放的权限是 "ROLE_" + users.role，而 admin 账号的 role 是
     * SUPER_ADMIN（权限 = ROLE_SUPER_ADMIN）。JWT 过滤器直接注入该单一权限、不走
     * GrantedAuthoritiesMapper，因此 RoleHierarchy（ROLE_SUPER_ADMIN > ROLE_ADMIN）不会在
     * getAuthorities() 里展开。历史上只精确匹配 ROLE_ADMIN，导致 SUPER_ADMIN 被当作普通用户，
     * 只能看到自己名下的数据（表现为"资源树里的 DEM 少了一大半"）。
     */
    public static Long getCurrentUserIdForDataFilter() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails) {
            CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
            boolean isAdmin = userDetails.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(auth -> auth.equals("ROLE_ADMIN") || auth.equals("ROLE_SUPER_ADMIN"));
            return isAdmin ? null : userDetails.getUserId();
        }
        return null;
    }
}
