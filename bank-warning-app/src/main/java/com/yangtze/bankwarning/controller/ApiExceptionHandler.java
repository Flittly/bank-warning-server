package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.domain.exception.ResultOverrideException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

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
