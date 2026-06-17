package com.yangtze.bankwarning.security.exception.business;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String message) {
        super(message);
    }
}
