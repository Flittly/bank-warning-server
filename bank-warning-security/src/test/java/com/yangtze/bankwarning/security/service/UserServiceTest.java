package com.yangtze.bankwarning.security.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceTest {

    @Test
    void superAdminCanManageAnyone() {
        assertTrue(UserService.canManage("SUPER_ADMIN", "SUPER_ADMIN", "ADMIN"));
        assertTrue(UserService.canManage("SUPER_ADMIN", "ADMIN", "USER"));
        assertTrue(UserService.canManage("SUPER_ADMIN", "USER", "SUPER_ADMIN"));
        assertTrue(UserService.canManage("SUPER_ADMIN", "USER", null));
    }

    @Test
    void adminCanOnlyManageUserAndGrantUserRole() {
        assertTrue(UserService.canManage("ADMIN", "USER", "USER"));
        assertTrue(UserService.canManage("ADMIN", "USER", null));
        assertFalse(UserService.canManage("ADMIN", "USER", "ADMIN"));
        assertFalse(UserService.canManage("ADMIN", "USER", "SUPER_ADMIN"));
        assertFalse(UserService.canManage("ADMIN", "ADMIN", null));
        assertFalse(UserService.canManage("ADMIN", "SUPER_ADMIN", null));
    }

    @Test
    void normalUserCannotManageAnyone() {
        assertFalse(UserService.canManage("USER", "USER", null));
        assertFalse(UserService.canManage("USER", "USER", "USER"));
        assertFalse(UserService.canManage("USER", "ADMIN", null));
    }
}
