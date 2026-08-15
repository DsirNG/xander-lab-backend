package com.xander.lab.admin.api;

/**
 * 创建模型供应商请求。apiKey 必填，由服务端加密后落库。
 */
public record CreateProviderRequest(
        String name,
        String vendor,
        String baseUrl,
        String apiKey,
        String description,
        Boolean enabled,
        Integer priority) {
}