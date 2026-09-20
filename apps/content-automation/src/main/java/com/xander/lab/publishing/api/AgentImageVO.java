package com.xander.lab.publishing.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/** Gallery projection for generated images; malformed legacy metadata is non-fatal. */
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

    public static AgentImageVO from(BlogMediaAssetVO asset) {
        String title = null;
        String prompt = null;
        String model = null;
        String size = null;
        if (asset.generationMeta() != null && !asset.generationMeta().isBlank()) {
            try {
                var node = MAPPER.readTree(asset.generationMeta());
                title = node.path("title").asText(null);
                prompt = node.path("prompt").asText(null);
                model = node.path("model").asText(null);
                size = node.path("size").asText(null);
            } catch (Exception ignored) {
                // Corrupt or legacy metadata must not hide the image itself.
            }
        }
        if (title == null || title.isBlank()) {
            title = asset.originalName();
        }
        return new AgentImageVO(asset.id(), asset.url(), title, prompt, model, size,
                asset.width(), asset.height(), asset.createdAt());
    }
}
