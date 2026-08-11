package com.yangtze.bankwarning.security.exception;

import com.yangtze.bankwarning.security.exception.business.InvalidCredentialsException;
import com.yangtze.bankwarning.security.exception.business.PermissionDeniedException;
import com.yangtze.bankwarning.security.exception.business.UserNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException e) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "VALIDATION_ERROR");
        
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        error.put("message", errorMessage);
        
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception e) {
        Map<String, String> error = new HashMap<>();
        error.put("code", "INTERNAL_ERROR");
        error.put("message", "服务器内部错误");
        return ResponseEntity.status(500).body(error);
    }
}
