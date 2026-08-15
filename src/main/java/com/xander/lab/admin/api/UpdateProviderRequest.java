package com.xander.lab.admin.api;

/**
 * 更新模型供应商请求。apiKey 为空表示保持原密钥不变。
 */
public record UpdateProviderRequest(
        String name,
        String vendor,
        String baseUrl,
        String apiKey,
        String description,
        Boolean enabled,
        Integer priority) {
}