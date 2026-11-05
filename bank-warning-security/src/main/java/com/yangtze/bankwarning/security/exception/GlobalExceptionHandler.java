package com.yangtze.bankwarning.security.exception;

import com.yangtze.bankwarning.security.exception.business.InvalidCredentialsException;
import com.yangtze.bankwarning.security.exception.business.PermissionDeniedException;
import com.yangtze.bankwarning.security.exception.business.UserNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 安全模块的可预期失败：用户不存在 404 / 凭证错误 401 / 无权操作 403。
 *
 * <p><b>为什么必须显式 {@link Ordered#HIGHEST_PRECEDENCE}</b>：Spring 的
 * {@code ExceptionHandlerExceptionResolver} 按 advice 顺序取「第一个能匹配的」整包胜出，
 * <b>不</b>跨 advice 比较异常精确度。类路径上还有一个 {@code ApiExceptionHandler}，
 * 它带 {@code @ExceptionHandler(Exception.class)} 兜底 —— 只要它排在前面，本类的三个
 * 专用 handler 就全是死代码，401/403/404 会统一变成 500。
 * 这个顺序过去是靠组件扫描顺序偶然决定的，这里把它钉死。
 *
 * <p><b>为什么不再声明 {@code MethodArgumentNotValidException} 与 {@code Exception}</b>：
 * 前者在 {@code ApiExceptionHandler} 里有一份同状态码、不同报文形状（{@code error.{code,message,details}}），
 * 保留两份等于让「参数校验失败」的报文形状随 advice 顺序漂移；后者与本类职责无关，
 * 且会再次把 {@code ApiExceptionHandler} 的 405/415/404 专用分支变成死代码。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUserNotFound(UserNotFoundException e) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "USER_NOT_FOUND");
        error.put("message", e.getMessage());
        return ResponseEntity.status(404).body(error);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCredentials(InvalidCredentialsException e) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "INVALID_CREDENTIALS");
        error.put("message", e.getMessage());
        return ResponseEntity.status(401).body(error);
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Map<String, String>> handlePermissionDenied(PermissionDeniedException e) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "PERMISSION_DENIED");
        error.put("message", e.getMessage());
        return ResponseEntity.status(403).body(error);
    }
}
