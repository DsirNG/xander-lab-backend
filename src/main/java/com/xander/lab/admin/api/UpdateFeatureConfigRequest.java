package com.xander.lab.admin.api;

/**
 * 更新功能模型配置请求。providerId/model 为空表示不使用该槽位；
 * fallback 槽位留空表示该功能无兜底模型。
 */
public record UpdateFeatureConfigRequest(
        Boolean enabled,
        Long primaryProviderId,
        String primaryModel,
        Long fallbackProviderId,
        String fallbackModel) {
}