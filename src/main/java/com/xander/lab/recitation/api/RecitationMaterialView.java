package com.xander.lab.recitation.api;

import java.time.LocalDateTime;

/** 返回给材料所有者的背诵材料视图。 */
public record RecitationMaterialView(
        Long id,
        String title,
        String content,
        int characterCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
