package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.domain.exception.BankGeometryException;
import com.yangtze.bankwarning.domain.exception.ResultOverrideException;
import com.yangtze.bankwarning.domain.exception.SectionManualInvalidException;
import com.yangtze.bankwarning.domain.exception.SectionRecomputeException;
import com.yangtze.bankwarning.domain.exception.TaskCopyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 全局兜底：业务异常 → 语义化状态码，未知异常 → 500。
 *
 * <p><b>与 {@code bank-warning-security} 的 {@code GlobalExceptionHandler} 的关系</b>：
 * Spring 的 {@code ExceptionHandlerExceptionResolver} 是「按 advice 顺序，第一个能匹配的
 * advice 整包胜出」，<b>不</b>跨 advice 比较异常精确度。两边都声明 {@code Exception} 兜底时，
 * 谁先谁后决定一切 —— 而被 {@code Exception} 接住的那一方，其专用 handler 全部变成死代码。
 * <p>因此这里显式声明 {@link Ordered#LOWEST_PRECEDENCE}：安全模块的 401/403/404
 * （用户不存在 / 凭证错误 / 无权操作）优先，本类只做应用层兜底。
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException exception) {
        log.warn("[api-error] not found: {}", exception.getMessage());
        return error("NOT_FOUND", exception.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException exception) {
        log.warn("[api-error] validation failed: {}", exception.getMessage());
        return error(
                "VALIDATION_ERROR",
                "参数验证失败",
                Map.of("message", exception.getBindingResult().getAllErrors().getFirst().getDefaultMessage()));
    }

    /**
     * 人工修改结果的两种可预期失败：400（入参不合法，留在编辑态）与 409（结果已过期，需要刷新）。
     * 不能用 IllegalArgumentException 兜：它被固定映射成 404，前端分不清「断面没了」和「该刷新了」。
     */
    @ExceptionHandler(ResultOverrideException.class)
    public ResponseEntity<Map<String, Object>> handleResultOverride(ResultOverrideException exception) {
        log.warn("[api-error] result override rejected({}): {}", exception.getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(error(exception.getCode(), exception.getMessage(), null));
    }

    /**
     * 单断面重算的三种「此刻不能算」：整任务在跑、同一断面已在重算、任务下有断面在单算。
     * 统一 409 —— 前端据此提示「稍后再试」而不是当成断面不存在。
     */
    @ExceptionHandler(SectionRecomputeException.class)
    public ResponseEntity<Map<String, Object>> handleSectionRecompute(SectionRecomputeException exception) {
        log.warn("[api-error] section recompute rejected({}): {}", exception.getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(error(
                        exception.getCode(),
                        exception.getMessage(),
                        exception.getSectionId() == null ? null : Map.of("section_id", exception.getSectionId())));
    }

    /**
     * 「断面人工无效」的入参不合法 = 400。
     *
     * <p>不能用 IllegalArgumentException 兜：它被固定映射成 404，前端会以为「断面已被删」
     * 从而关掉弹窗 —— 而真实原因是请求缺了 {@code invalid} 字段。用户看到的现象会是
     * "点了按钮，弹窗自己关了，什么都没发生"，比报错更难查。
     */
    @ExceptionHandler(SectionManualInvalidException.class)
    public ResponseEntity<Map<String, Object>> handleSectionManualInvalid(SectionManualInvalidException exception) {
        log.warn("[api-error] manual invalid rejected({}): {}", exception.getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(error(exception.getCode(), exception.getMessage(), null));
    }

    /**
     * 复制任务的三种「可预期的不能做」：目标 id 撞车 / 源任务在跑（结果不完整）= 409，
     * 入参不合法 = 400，跨用户未开放 = 403。不能拿 IllegalArgumentException 兜成 404，
     * 否则前端分不清「任务没了」和「换个名字再试」。
     */
    @ExceptionHandler(TaskCopyException.class)
    public ResponseEntity<Map<String, Object>> handleTaskCopy(TaskCopyException exception) {
        log.warn("[api-error] task copy rejected({}): {}", exception.getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(error(exception.getCode(), exception.getMessage(), null));
    }

    /**
     * 岸段几何编辑的两种可预期失败：几何不合法 = 400（前端回滚这一次改动、留在编辑态），
     * 岸段不存在 / 已被删 = 404。同样不能用 IllegalArgumentException 兜 ——
     * 它被固定映射成 404，会让「折点不够」看起来像「岸段没了」。
     */
    @ExceptionHandler(BankGeometryException.class)
    public ResponseEntity<Map<String, Object>> handleBankGeometry(BankGeometryException exception) {
        log.warn("[api-error] bank geometry rejected({}): {}", exception.getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(error(exception.getCode(), exception.getMessage(), null));
    }

    /**
     * HTTP 方法用错 = 405，不是 500。
     *
     * <p>这条以前掉进 {@link #handleGeneric}，于是「请求方法不对」被记成
     * {@code internal error} 并打满 97 行栈。后果有三：
     * <ol>
     *   <li>客户端拿到 500 而不是 405，监控里「探针方法配错」与「服务真崩」无法区分；</li>
     *   <li>健康检查探针打错接口时，每次 ~16KB 堆栈，5 秒一轮 ≈ 270MB/天；</li>
     *   <li>真正的 500 被这堆噪音淹没。</li>
     * </ol>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Map<String, Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        log.warn("[api-error] method not allowed: {} (supported: {})", exception.getMethod(), supported);
        return error(
                "METHOD_NOT_ALLOWED",
                exception.getMessage(),
                supported == null ? null : Map.<String, Object>of("supported", supported));
    }

    /**
     * 请求体的 Content-Type 不支持 = 415，不是 500。同样是「请求本身有问题」，与代码缺陷无关。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Map<String, Object> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        log.warn("[api-error] unsupported media type: {} (supported: {})",
                exception.getContentType(), exception.getSupportedMediaTypes());
        return error("UNSUPPORTED_MEDIA_TYPE", exception.getMessage(), null);
    }

    /**
     * 路径不存在 = 404，不是 500。Spring 6.1+ 起「没有匹配的 handler」由
     * {@code ResourceHttpRequestHandler} 抛 {@link NoResourceFoundException}，
     * 不加这条就会掉进兜底被记成内部错误 —— 用户敲错一个 URL 也会让日志红一片。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleNoResource(NoResourceFoundException exception) {
        log.warn("[api-error] no handler for path: {}", exception.getResourcePath());
        return error("NOT_FOUND", exception.getMessage(), null);
    }

    /**
     * 认证失败 = 401。{@code AuthController#login} 里 {@code authenticationManager.authenticate()}
     * 抛的是 Spring Security 的 {@code BadCredentialsException} / {@code UsernameNotFoundException}
     * （不是 security 模块自定义的 {@code InvalidCredentialsException}），以前掉进兜底变成 500 ——
     * 前端于是只能显示「登录失败 (500)」，看不到原因。
     *
     * <p>对外统一话术「用户名或密码错误」，不回显原始 message：区分「用户不存在」和「密码错」
     * 等于把账号枚举接口白送出去。
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, Object> handleAuthentication(AuthenticationException exception) {
        log.warn("[api-error] authentication failed: {}", exception.getMessage());
        return error("INVALID_CREDENTIALS", "用户名或密码错误", null);
    }

    /**
     * 已登录但权限不足 = 403。{@code @PreAuthorize} 抛出的 {@code AccessDeniedException}
     * 在方法级生效，不会经过过滤器链的 ExceptionTranslationFilter，以前同样掉进兜底变 500 ——
     * 普通用户点管理入口看到的是「服务器内部错误」而不是「无权操作」。
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> handleAccessDenied(AccessDeniedException exception) {
        log.warn("[api-error] access denied: {}", exception.getMessage());
        return error("PERMISSION_DENIED", "无权执行该操作", null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleGeneric(Exception exception) {
        log.error("[api-error] internal error: {}", exception.getMessage(), exception);
        return error("INTERNAL_ERROR", exception.getMessage(), null);
    }

    private Map<String, Object> error(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("details", details == null ? Map.of() : details);
        return Map.of("error", payload);
    }
}
