package com.yangtze.bankwarning.security.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    /**
     * 当前请求的**数据可见范围**，把「是谁」拆成三个互不混淆的维度。
     *
     * <p>存在的理由：{@link #getCurrentUserIdForDataFilter()} 用同一个 {@code null}
     * 同时表示「我是管理员，看全部」和「我读不到身份」，调用方无法区分，于是
     * 「读不到身份」被自然解释成「管理员」⇒ 过滤条件消失 —— 这是 **fail-open**，
     * 安全设计里最不能要的一种。任何**不一定跑在 HTTP 请求线程上**的代码
     * （异步任务、线程池、AI 工具执行）都必须改用本方法，并且以
     * {@link #authenticated()} 为准 fail-closed。
     *
     * @param authenticated 是否真的解析出了登录用户（false = 上下文中没有身份，**不等于**管理员）
     * @param admin         是否管理员（仅在 authenticated 为 true 时有意义）
     * @param userId        普通用户的 ID；管理员与未认证均为 null
     */
    public record DataScope(boolean authenticated, boolean admin, Long userId) {

        /** 上下文中没有身份：既不是管理员，也没有 userId。调用方必须拒绝或返回空。 */
        public static final DataScope ANONYMOUS = new DataScope(false, false, null);

        /** 是否应看到全部数据（未认证永远 false —— 这一行就是 fail-closed 的落点）。 */
        public boolean seeAll() {
            return authenticated && admin;
        }
    }

    /**
     * 取当前请求的数据可见范围。
     *
     * <p>匿名认证（{@code AnonymousAuthenticationToken}）的 principal 是字符串
     * {@code "anonymousUser"}，不是 {@link CustomUserDetails}，因此会落到
     * {@link DataScope#ANONYMOUS}，不会被误判成管理员。
     */
    public static DataScope getDataScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return DataScope.ANONYMOUS;
        }
        boolean isAdmin = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(auth -> auth.equals("ROLE_ADMIN") || auth.equals("ROLE_SUPER_ADMIN"));
        return new DataScope(true, isAdmin, isAdmin ? null : userDetails.getUserId());
    }

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
     *
     * <p><b>⚠ 只可用于确定处于 HTTP 请求线程的代码。</b>返回的 {@code null} 有双重语义
     * （管理员 / 读不到身份），跨线程调用（线程池、AI 工具执行、WebFlux 异步链）时
     * ThreadLocal 为空，会静默退化成「不过滤 = 看全部」。需要区分这两种情况的场景请改用
     * {@link #getDataScope()}，并以 {@code authenticated} 为准 fail-closed。
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
