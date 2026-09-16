package com.xander.lab.admin.api;

import java.time.LocalDateTime;

/**
 * 功能模型配置视图（API 契约）。
 * featureKey 取值：agent / blog_agent / blog_agent_image / img2three_vision。
 * 为空的功能字段表示未配置；前端需要供应商名称时用供应商列表自行关联。
 * apiStyle 取值：IMAGES_GENERATIONS / CHAT_COMPLETIONS / CHAT_PROMPT / RESPONSES / DASHSCOPE_ASR，为空表示按功能取默认。
 */
public record FeatureConfigView(
        String featureKey,
        boolean enabled,
        Long primaryProviderId,
        String primaryModel,
        String primaryApiStyle,
        Long fallbackProviderId,
        String fallbackModel,
        String fallbackApiStyle,
        LocalDateTime updatedAt) {
}
