package com.xander.lab.recitation.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 返回给知识所有者的一次测验评分视图。
 *
 * @param items 逐题证据（题目、作答、得分、点评），由 items_json 反序列化而来
 */
public record KnowledgeQuizAttemptView(
        Long id,
        Long materialId,
        String testMode,
        int questionCount,
        int correctCount,
        BigDecimal score,
        String verdict,
        Object items,
        LocalDateTime createdAt) {
}
