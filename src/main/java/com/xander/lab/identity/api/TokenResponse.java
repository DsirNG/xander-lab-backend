package com.xander.lab.identity.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 登录 / Token 刷新响应体
 * 对应前端 axios 封装中解析的字段：
 *   const { accessToken, refreshToken } = response.data?.data ?? {}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    /** 访问令牌（有效期 2 小时） */
    private String accessToken;

    /** 刷新令牌（有效期 7 天） */
    private String refreshToken;

    /** 令牌类型，固定为 Bearer */
    private String tokenType;

    /** Access Token 有效期（秒） */
    private long expiresIn;

    /** 用户信息 */
    private UserInfo userInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private String username;
        private String nickname;
        private String avatar;
        private String role;
        /** 邮箱（登录账号，展示用，不支持修改） */
        private String email;
        /** 注册时间（账户信息页展示） */
        private LocalDateTime createdAt;
    }
}
