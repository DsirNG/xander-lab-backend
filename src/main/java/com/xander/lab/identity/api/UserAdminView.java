package com.xander.lab.identity.api;

import java.time.LocalDateTime;

/**
 * 管理台用户视图（API 契约）。绝不含密码或 token 信息。
 */
public record UserAdminView(
        Long id,
        String username,
        String nickname,
        String email,
        String role,
        Integer status,
        LocalDateTime createdAt) {
}