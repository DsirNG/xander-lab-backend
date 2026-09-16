package com.xander.lab.recitation.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 返回给任务所有者的录音识别与背诵报告视图。 */
public record RecitationAttemptView(
        Long id,
        Long materialId,
        String status,
        String audioUrl,
        String transcript,
        Object result,
        BigDecimal score,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
