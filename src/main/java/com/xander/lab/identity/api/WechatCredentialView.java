package com.xander.lab.identity.api;

import java.time.LocalDateTime;

/**
 * 微信登录凭据视图（API 契约）。
 * AppSecret 永不下发；secretConfigured 仅说明凭据是否已完成配置。
 */
public record WechatCredentialView(
        String appId,
        boolean secretConfigured,
        LocalDateTime updatedAt) {
}