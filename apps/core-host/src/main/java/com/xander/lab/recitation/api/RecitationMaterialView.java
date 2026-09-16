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
        /** 非空表示这条知识已归档：不出现在默认列表，也不再参与到期复习。 */
        LocalDateTime archivedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
