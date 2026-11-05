package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.security.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「请求错 ≠ 服务错」回归。
 *
 * <p>线上日志曾被 35 条同一种 ERROR 刷满（3 分钟内）：健康检查探针用 GET 打只接受 POST 的
 * {@code /v0/auth/login}，Spring 抛 {@code HttpRequestMethodNotSupportedException}，
 * 而 {@link ApiExceptionHandler} 当时只有 {@code Exception} 兜底 —— 于是 405 被记成
 * {@code internal error} 并打满 97 行堆栈，每次约 16KB、5 秒一轮。
 *
 * <p>本测试锁住三条不能退化的事：
 * <ol>
 *   <li>「方法 / 类型 / 身份 / 权限」用错必须给出 4xx + 可识别的 code，不能落进 500 兜底；</li>
 *   <li>真正的服务端异常仍必须给 500，别为了消噪音把兜底也一起吃掉；</li>
 *   <li>两个 {@code @RestControllerAdvice} 的先后顺序必须显式固定 —— 否则 {@code Exception}
 *       兜底会把另一方的专用 handler 全部吃掉，且报文形状会随扫描顺序漂移。</li>
 * </ol>
 */
class ApiExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    @DisplayName("GET 打只接受 POST 的接口 → 405 + METHOD_NOT_ALLOWED，不再被兜底成 500")
    void methodNotSupportedBecomes405() throws Exception {
        mockMvc.perform(get("/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("Content-Type 不被接受 → 415 + UNSUPPORTED_MEDIA_TYPE")
    void mediaTypeNotSupportedBecomes415() throws Exception {
        mockMvc.perform(post("/probe/body")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("真正的服务端异常仍然是 500，兜底逻辑没被削弱")
    void unexpectedFailureStillIs500() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("密码错 → 401，且不回显原始 message（防账号枚举）")
    void authenticationFailureBecomes401() throws Exception {
        mockMvc.perform(get("/probe/auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("@PreAuthorize 拒绝 → 403，不再是 500")
    void accessDeniedBecomes403() throws Exception {
        mockMvc.perform(get("/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));
    }

    /**
     * 两个 advice 都声明 {@code Exception} 兜底时，「谁先」决定谁的专用 handler 全部变死代码。
     * {@code ExceptionHandlerExceptionResolver} 正是用 {@link AnnotationAwareOrderComparator}
     * 排序 advice 的，这里直接复现该排序：无论注册顺序如何，安全模块都必须排在前面。
     */
    @Test
    @DisplayName("安全模块 advice 必须先于应用层兜底，否则 401/403/404 全退化成 500")
    void securityAdviceRunsBeforeAppAdvice() {
        List<Object> advices = new ArrayList<>(List.of(new ApiExceptionHandler(), new GlobalExceptionHandler()));
        AnnotationAwareOrderComparator.sort(advices);

        assertEquals(GlobalExceptionHandler.class, advices.get(0).getClass(),
                "安全模块的 401/403/404 必须优先于应用层兜底");
        assertEquals(ApiExceptionHandler.class, advices.get(1).getClass());
    }

    /** 只为让 Advice 有东西可兜；不代表真实业务接口。 */
    @RestController
    static class ProbeController {

        @PostMapping("/probe")
        String create() {
            return "ok";
        }

        @PostMapping(value = "/probe/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody String payload) {
            return payload;
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/probe/auth")
        String auth() {
            throw new BadCredentialsException("Bad credentials");
        }

        @GetMapping("/probe/denied")
        String denied() {
            throw new AccessDeniedException("denied");
        }
    }
}
