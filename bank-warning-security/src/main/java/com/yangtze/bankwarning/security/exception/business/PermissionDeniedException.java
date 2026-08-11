package com.yangtze.bankwarning.security.exception.business;

/** 用户管理权限不足（403） */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
