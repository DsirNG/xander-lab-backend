package com.xander.lab.publishing.api;

import java.time.LocalDateTime;

/** HTTP read model for a user-owned blog media asset. */
public record BlogMediaAssetVO(
        Long id,
        Long userId,
        String url,
        String originalName,
        String storedName,
        Long size,
        String contentType,
        Integer width,
        Integer height,
        String sourceType,
        Long agentTaskId,
        String generationMeta,
        LocalDateTime createdAt) {
}
