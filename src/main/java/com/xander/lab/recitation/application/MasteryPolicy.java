package com.xander.lab.recitation.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 将一次测试分数转换为掌握等级和间隔复习时间。 */
public final class MasteryPolicy {
    private MasteryPolicy() {
    }

    public static Result evaluate(BigDecimal score, LocalDateTime reviewedAt) {
        int value = score == null ? 0 : score.intValue();
        if (value >= 90) return new Result("MASTERED", reviewedAt.plusDays(30));
        if (value >= 75) return new Result("APPLYING", reviewedAt.plusDays(7));
        if (value >= 60) return new Result("RECALLING", reviewedAt.plusDays(3));
        if (value >= 30) return new Result("FAMILIAR", reviewedAt.plusDays(1));
        return new Result("NEW", reviewedAt.plusHours(12));
    }

    public record Result(String level, LocalDateTime nextReviewAt) {
    }
}
