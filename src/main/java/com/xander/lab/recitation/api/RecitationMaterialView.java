package com.xander.lab.recitation.api;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/** 返回给材料所有者的背诵材料视图。 */
public record RecitationMaterialView(
        Long id,
        String title,
        String content,
        int characterCount,
        String knowledgeType,
        String testMode,
        BigDecimal masteryScore,
        String masteryLevel,
        int reviewCount,
        LocalDateTime lastReviewAt,
        LocalDateTime nextReviewAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
