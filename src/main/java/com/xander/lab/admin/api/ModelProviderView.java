package com.xander.lab.admin.api;

import java.time.LocalDateTime;

/**
 * 模型供应商视图（API 契约）。
 * apiKey 永不下发；apiKeyConfigured 仅说明该供应商是否已配置密钥。
 */
public record ModelProviderView(
        Long id,
        String name,
        String vendor,
        String baseUrl,
        boolean apiKeyConfigured,
        String description,
        boolean enabled,
        int priority,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}