package com.xander.lab.publishing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.publishing.domain.BlogMediaAsset;

import java.time.LocalDateTime;

/**
 * 智能体生成图片的列表视图。
 * 负责把 {@link BlogMediaAsset#getGenerationMeta()} 中的生成参数（标题、提示词、模型、尺寸）
 * 解析为前端图片画廊可直接使用的字段；损坏或缺失的元数据不阻断展示。
 */
public record AgentImageVO(
        Long id,
        String url,
        String title,
        String prompt,
        String model,
        String size,
        Integer width,
        Integer height,
        LocalDateTime createdAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AgentImageVO from(BlogMediaAsset asset) {
        String title = null;
        String prompt = null;
        String model = null;
        String size = null;
        if (asset.getGenerationMeta() != null && !asset.getGenerationMeta().isBlank()) {
            try {
                var node = MAPPER.readTree(asset.getGenerationMeta());
                title = node.path("title").asText(null);
                prompt = node.path("prompt").asText(null);
                model = node.path("model").asText(null);
                size = node.path("size").asText(null);
            } catch (Exception ignored) {
                // 损坏或旧版元数据：保留空字段继续展示图片本身。
            }
        }
        if (title == null || title.isBlank()) title = asset.getOriginalName();
        return new AgentImageVO(
                asset.getId(),
                asset.getUrl(),
                title,
                prompt,
                model,
                size,
                asset.getWidth(),
                asset.getHeight(),
                asset.getCreatedAt());
    }
}