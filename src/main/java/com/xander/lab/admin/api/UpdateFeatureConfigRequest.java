package com.xander.lab.admin.api;

/**
 * 更新功能模型配置请求。providerId/model/apiStyle 为空表示不使用该槽位；
 * fallback 槽位留空表示该功能无兜底模型。
 * apiStyle 取值：IMAGES_GENERATIONS / CHAT_COMPLETIONS / RESPONSES，留空按功能取默认。
 */
public record UpdateFeatureConfigRequest(
        Boolean enabled,
        Long primaryProviderId,
        String primaryModel,
        String primaryApiStyle,
        Long fallbackProviderId,
        String fallbackModel,
        String fallbackApiStyle) {
}