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

    /** 访问令牌；旧模式沿用部署配置，新 Refresh 模式按 Wave 2 的 TTL 配置切流 */
    private String accessToken;

    /** 刷新令牌；旧模式为 JWT，新 Refresh 模式为 opaque session token */
    private String refreshToken;

    /** 令牌类型，固定为 Bearer */
    private String tokenType;

    /** Access Token 有效期（秒） */
    private long expiresIn;

    /** 用户信息 */
    private UserInfo userInfo;

    /** 是否需要在微信登录流程中绑定邮箱（邮箱为空时 true，前端据此弹出绑定引导） */
    private boolean needsEmailBinding;

    /** 首次微信登录（openid 未注册）为 true：未建号，返回待绑定凭证由前端引导绑定或跳过 */
    private boolean pendingBind;

    /** 待绑定凭证（pendingBind 为 true 时非空，10 分钟有效，仅用于绑定/跳过端点） */
    private String pendingBindToken;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private String username;
        private String nickname;
        private String avatar;
        private String role;
        /** 订阅等级：FREE / PLUS / PRO / ULTRA */
        private String tier;
        /** 当前订阅到期时间；免费用户为空 */
        private LocalDateTime tierExpiresAt;
        /** 邮箱（登录账号，展示用，不支持修改） */
        private String email;
        /** 注册时间（账户信息页展示） */
        private LocalDateTime createdAt;
    }
}
